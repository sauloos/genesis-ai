package ai.genesisbrands.standing;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.platform.standing.StandingAgent;
import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import ai.genesisbrands.service.GenericSingleShotAgentService;
import ai.genesisbrands.usage.UsageContext;
import ai.genesisbrands.usage.UsageScope;
import ai.genesisbrands.usage.UsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts and executes standing runs. A run is recorded before it starts, under a unique
 * {@code (subscription, slot)}, so a slot two replicas both claim still runs once. Execution
 * happens on a small dedicated pool (not the app's {@code @Async} executor, so a slow agent
 * never starves request work) under a {@link UsageScope} carrying the client, agent and run
 * id: every model call the agent makes is metered to this run.
 */
@Slf4j
@Service
public class StandingRunner {

    static final int POOL_SIZE = 2;

    private final StandingSubscriptionRepository subscriptions;
    private final StandingRunRepository runs;
    private final InboxItemRepository inbox;
    private final StandingCatalog catalog;
    private final StandingContextService context;
    private final UsageService usage;
    private final GenericSingleShotAgentService models;
    private final StandingAttachments attachments;
    private final StandingNotifier notifier;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readOnly;
    private final ExecutorService pool;

    public StandingRunner(StandingSubscriptionRepository subscriptions, StandingRunRepository runs, InboxItemRepository inbox,
                          StandingCatalog catalog, StandingContextService context, UsageService usage,
                          GenericSingleShotAgentService models, StandingAttachments attachments,
                          StandingNotifier notifier, ObjectMapper objectMapper,
                          PlatformTransactionManager transactions) {
        this.subscriptions = subscriptions;
        this.runs = runs;
        this.inbox = inbox;
        this.catalog = catalog;
        this.context = context;
        this.usage = usage;
        this.models = models;
        this.attachments = attachments;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
            Thread t = new Thread(r, "standing-run-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    /**
     * Records a run for {@code slot} and queues it, or records a skip when the subscription may
     * not run now (offer withdrawn, monthly cap or budget reached). Returns null when the slot
     * already has a run.
     */
    public StandingRun start(StandingSubscription sub, Instant slot, String trigger) {
        String refusal = refusal(sub);
        StandingRun run = new StandingRun();
        run.setId(UUID.randomUUID().toString());
        run.setSubscriptionId(sub.getId());
        run.setClientUserId(sub.getClientUserId());
        run.setAgentId(sub.getAgentId());
        run.setSlot(slot);
        run.setTrigger(trigger);
        run.setStartedAt(Instant.now());
        if (refusal == null) {
            run.setStatus(StandingRun.RUNNING);
        } else {
            run.setStatus(StandingRun.SKIPPED);
            run.setNote(refusal);
            run.setFinishedAt(run.getStartedAt());
        }
        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            log.info("Standing slot {} for subscription {} already has a run", slot, sub.getId());
            return null;
        }
        if (refusal == null) pool.submit(() -> execute(run.getId()));
        return run;
    }

    /** Why this subscription may not run now, or null when it may. */
    public String refusal(StandingSubscription sub) {
        if (!catalog.isOffered(sub.getAgentId())) return "This agent is no longer offered.";
        if (!catalog.isOfferedTo(sub.getAgentId(), sub.getClientUserId())) return "This agent is not part of your current plan.";
        StandingAgentOffer offer = catalog.offer(sub.getAgentId());
        Instant monthStart = monthStart();
        if (offer.getMaxRunsPerMonth() != null) {
            long used = runs.countBySubscriptionIdAndStatusInAndStartedAtGreaterThanEqual(
                sub.getId(), List.of(StandingRun.RUNNING, StandingRun.SUCCEEDED), monthStart);
            if (used >= offer.getMaxRunsPerMonth()) {
                return "This month's limit of " + offer.getMaxRunsPerMonth() + " run(s) is used up.";
            }
        }
        if (offer.getMonthlyBudgetMicros() != null
            && usage.spentThisMonthMicros(sub.getClientUserId()) >= offer.getMonthlyBudgetMicros()) {
            return "This month's usage allowance is used up.";
        }
        return null;
    }

    void execute(String runId) {
        StandingRun run = runs.findById(runId).orElse(null);
        if (run == null) return;
        try {
            StandingSubscription sub = subscriptions.findById(run.getSubscriptionId())
                .orElseThrow(() -> new IllegalStateException("The subscription was removed."));
            StandingAgent agent = catalog.require(run.getAgentId());
            ClientUser client = context.client(run.getClientUserId())
                .orElseThrow(() -> new IllegalStateException("The client account no longer exists."));
            Map<String, String> options = catalog.effectiveOptions(agent, catalog.offer(agent.agentId()), catalog.readMap(sub.getOptionsJson()));
            List<StandingRunContext.Decision> decisions = inbox.findByClientUserIdAndAgentIdAndStatusInOrderByDecidedAtDesc(
                    client.getId(), agent.agentId(), List.of(InboxItem.APPROVED, InboxItem.REJECTED), PageRequest.of(0, 10))
                .stream().map(i -> new StandingRunContext.Decision(i.getTitle(), i.getStatus(), i.getFeedback(), i.getDecidedAt()))
                .toList();
            Instant previous = runs.findFirstBySubscriptionIdAndStatusAndStartedAtLessThanOrderByStartedAtDesc(
                sub.getId(), StandingRun.SUCCEEDED, run.getStartedAt()).map(StandingRun::getStartedAt).orElse(null);
            String clientName = client.getName() != null && !client.getName().isBlank() ? client.getName() : client.getEmail();

            UsageScope scope = UsageScope.of(client.getId(), agent.agentId(), "standing", runId);
            StandingRunResult result = UsageContext.callWith(scope, () -> {
                String clientContext = readOnly.execute(tx -> context.build(client, agent.agentId()));
                return agent.run(new StandingRunContext(runId, sub.getId(), client.getId(), clientName, options,
                    clientContext, decisions, previous, Instant.now(), sub.getNextRunAt(), zone(sub),
                    prompt -> models.generate(agent.agentId(), prompt, null), sub.getAgentMemory()));
            });

            List<StandingRunResult.Item> items = result == null || result.items() == null ? List.of() : result.items();
            for (StandingRunResult.Item item : items) inbox.save(toInboxItem(run, item));
            run.setItemsCreated(items.size());
            run.setNote(truncate(result == null ? null : result.note(), 500));
            run.setStatus(StandingRun.SUCCEEDED);
            if (result != null && result.memory() != null) saveMemory(sub.getId(), result.memory());
            notifier.itemsArrived(client, sub, items.size());
        } catch (Exception e) {
            log.warn("Standing run {} ({}) failed: {}", runId, run.getAgentId(), e.toString());
            run.setStatus(StandingRun.FAILED);
            run.setError(truncate(e.getMessage() == null ? e.toString() : e.getMessage(), 4000));
        } finally {
            run.setFinishedAt(Instant.now());
            try {
                run.setCostMicros(usage.costOfRunMicros(runId));
            } catch (RuntimeException e) {
                log.warn("Could not total the cost of standing run {}: {}", runId, e.getMessage());
            }
            runs.save(run);
        }
    }

    private InboxItem toInboxItem(StandingRun run, StandingRunResult.Item item) throws Exception {
        InboxItem i = new InboxItem();
        i.setId(UUID.randomUUID().toString());
        i.setClientUserId(run.getClientUserId());
        i.setAgentId(run.getAgentId());
        i.setSubscriptionId(run.getSubscriptionId());
        i.setRunId(run.getId());
        i.setKind(item.kind() == null ? StandingRunResult.Item.REPORT : truncate(item.kind(), 20));
        i.setTitle(truncate(item.title() == null || item.title().isBlank() ? catalog.displayName(run.getAgentId()) : item.title(), 300));
        i.setSummary(truncate(item.summary(), 1000));
        i.setBody(item.body());
        i.setPayloadJson(item.payload() == null || item.payload().isEmpty() ? null : objectMapper.writeValueAsString(item.payload()));
        i.setAttachmentsJson(attachments.toJson(attachments.store(run.getClientUserId(), i.getId(), item.attachments())));
        return i;
    }

    /** Writes only the memory column, so a settings change made during the run is kept. */
    private void saveMemory(String subscriptionId, String memory) {
        if (memory.length() > StandingRunResult.MAX_MEMORY) {
            log.warn("Standing memory for subscription {} is {} characters; not kept", subscriptionId, memory.length());
            return;
        }
        subscriptions.saveMemory(subscriptionId, memory);
    }

    private static ZoneId zone(StandingSubscription sub) {
        try {
            return ZoneId.of(sub.getTimezone());
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    static Instant monthStart() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
