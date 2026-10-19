package ai.genesisbrands.standing;

import ai.genesisbrands.platform.AgentCustomOption;
import ai.genesisbrands.platform.CoreAgent;
import ai.genesisbrands.platform.standing.Cadence;
import ai.genesisbrands.platform.standing.StandingAgent;
import ai.genesisbrands.repository.ClientUserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Client and admin operations over subscriptions, runs and the inbox. Every client operation
 * takes the client id the caller resolved server-side (session or connector key) and only
 * ever touches that client's own rows.
 */
@Service
public class StandingService {

    /** A client may start a run by hand at most this often per subscription. */
    static final Duration MANUAL_RUN_SPACING = Duration.ofMinutes(10);

    private final StandingCatalog catalog;
    private final StandingSubscriptionRepository subscriptions;
    private final StandingRunRepository runs;
    private final InboxItemRepository inbox;
    private final StandingOfferRepository offers;
    private final StandingRunner runner;
    private final ClientUserRepository clientUsers;
    private final ObjectMapper objectMapper;

    public StandingService(StandingCatalog catalog, StandingSubscriptionRepository subscriptions, StandingRunRepository runs,
                           InboxItemRepository inbox, StandingOfferRepository offers, StandingRunner runner,
                           ClientUserRepository clientUsers, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.subscriptions = subscriptions;
        this.runs = runs;
        this.inbox = inbox;
        this.offers = offers;
        this.runner = runner;
        this.clientUsers = clientUsers;
        this.objectMapper = objectMapper;
    }

    // ---- views -------------------------------------------------------------------------

    public record ClientOption(String key, String label, AgentCustomOption.OptionType type, List<String> allowedValues,
                               String defaultValue, String helpText, boolean locked) {}

    public record ClientOffer(String agentId, String displayName, String description, String icon,
                              List<ClientOption> options, List<Cadence> cadences, Cadence defaultCadence,
                              Integer maxRunsPerMonth, SubscriptionView subscription) {}

    public record SubscriptionView(String id, String agentId, boolean active, String pausedReason, Cadence cadence,
                                   int dayOfWeek, int dayOfMonth, String timeOfDay, String timezone,
                                   Map<String, String> options, Instant nextRunAt, Instant lastRunAt, long runsThisMonth) {}

    public record RunView(String id, String agentId, String agentName, String trigger, String status, Instant slot,
                          Instant startedAt, Instant finishedAt, int itemsCreated, long costMicros, String note, String error) {}

    public record InboxView(String id, String agentId, String agentName, String kind, String title, String summary,
                            String body, Object payload, String status, String feedback, Instant createdAt, Instant decidedAt) {}

    public record SubscriptionRequest(Cadence cadence, Integer dayOfWeek, Integer dayOfMonth, String timeOfDay,
                                      String timezone, Map<String, String> options, Boolean active) {}

    // ---- client: offers and subscriptions ---------------------------------------------

    public List<ClientOffer> offersFor(String clientUserId) {
        List<ClientOffer> out = new ArrayList<>();
        for (StandingAgent agent : catalog.all()) {
            if (!catalog.isOffered(agent.agentId())) continue;
            StandingAgentOffer offer = catalog.offer(agent.agentId());
            Set<String> locked = catalog.locked(offer);
            CoreAgent meta = catalog.describe(agent.agentId()).orElse(null);
            List<ClientOption> options = catalog.optionsWithAdminDefaults(agent, offer).stream()
                .map(o -> new ClientOption(o.key(), o.label(), o.type(), o.allowedValues(), o.defaultValue(), o.helpText(), locked.contains(o.key())))
                .toList();
            SubscriptionView sub = subscriptions.findByClientUserIdAndAgentId(clientUserId, agent.agentId())
                .map(s -> view(s, agent, offer)).orElse(null);
            out.add(new ClientOffer(agent.agentId(),
                meta == null ? agent.agentId() : meta.displayName(),
                meta == null ? null : meta.description(),
                meta == null ? null : meta.icon(),
                options, List.copyOf(catalog.cadences(agent, offer)), catalog.defaultCadence(agent, offer),
                offer.getMaxRunsPerMonth(), sub));
        }
        return out;
    }

    /** Creates or replaces the client's subscription to an offered agent. */
    public SubscriptionView subscribe(String clientUserId, String agentId, SubscriptionRequest req) {
        StandingAgent agent = catalog.requireOffered(agentId);
        StandingAgentOffer offer = catalog.offer(agentId);
        Cadence cadence = req.cadence() == null ? catalog.defaultCadence(agent, offer) : req.cadence();
        if (!catalog.cadences(agent, offer).contains(cadence)) throw badRequest("That frequency is not available for this agent.");
        int dayOfWeek = req.dayOfWeek() == null ? 1 : req.dayOfWeek();
        int dayOfMonth = req.dayOfMonth() == null ? 1 : req.dayOfMonth();
        String timeOfDay = req.timeOfDay() == null || req.timeOfDay().isBlank() ? "09:00" : req.timeOfDay().trim();
        String timezone = req.timezone() == null || req.timezone().isBlank() ? "UTC" : req.timezone().trim();

        StandingSchedule schedule;
        Map<String, String> options;
        try {
            schedule = new StandingSchedule(cadence, dayOfWeek, dayOfMonth, LocalTime.parse(timeOfDay), ZoneId.of(timezone));
            // Validates every value now; locked keys are dropped, so the stored client values
            // never shadow an admin default the admin later changes.
            catalog.effectiveOptions(agent, offer, req.options());
            options = new HashMap<>();
            Set<String> locked = catalog.locked(offer);
            Map<String, String> declared = new HashMap<>();
            agent.subscriptionOptions().forEach(o -> declared.put(o.key(), o.key()));
            if (req.options() != null) {
                req.options().forEach((k, v) -> {
                    if (declared.containsKey(k) && !locked.contains(k)) {
                        AgentCustomOption o = agent.subscriptionOptions().stream().filter(x -> x.key().equals(k)).findFirst().orElseThrow();
                        options.put(k, OptionValues.normalize(o, v));
                    }
                });
            }
        } catch (DateTimeParseException e) {
            throw badRequest("Time of day must look like 09:00.");
        } catch (DateTimeException e) {
            throw badRequest("Unknown time zone: " + timezone);
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }

        StandingSubscription sub = subscriptions.findByClientUserIdAndAgentId(clientUserId, agentId).orElseGet(() -> {
            StandingSubscription fresh = new StandingSubscription();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setClientUserId(clientUserId);
            fresh.setAgentId(agentId);
            fresh.setActive(true);
            return fresh;
        });
        if (StandingSubscription.PAUSED_BY_ADMIN.equals(sub.getPausedReason())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This subscription was paused by the administrator.");
        }
        sub.setCadence(cadence.name());
        sub.setDayOfWeek(dayOfWeek);
        sub.setDayOfMonth(dayOfMonth);
        sub.setTimeOfDay(LocalTime.parse(timeOfDay).truncatedTo(ChronoUnit.MINUTES).toString());
        sub.setTimezone(timezone);
        sub.setOptionsJson(catalog.writeMap(options));
        if (req.active() != null) {
            sub.setActive(req.active());
            sub.setPausedReason(req.active() ? null : StandingSubscription.PAUSED_BY_CLIENT);
        }
        sub.setNextRunAt(sub.isActive() ? schedule.next(Instant.now(), null) : null);
        sub.setUpdatedAt(Instant.now());
        return view(subscriptions.save(sub), agent, offer);
    }

    public SubscriptionView pause(String clientUserId, String agentId) {
        StandingSubscription sub = ownSubscription(clientUserId, agentId);
        if (sub.isActive()) {
            sub.setActive(false);
            sub.setPausedReason(StandingSubscription.PAUSED_BY_CLIENT);
            sub.setNextRunAt(null);
            sub.setUpdatedAt(Instant.now());
            subscriptions.save(sub);
        }
        return view(sub);
    }

    public SubscriptionView resume(String clientUserId, String agentId) {
        catalog.requireOffered(agentId);
        StandingSubscription sub = ownSubscription(clientUserId, agentId);
        if (StandingSubscription.PAUSED_BY_ADMIN.equals(sub.getPausedReason())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This subscription was paused by the administrator.");
        }
        if (!sub.isActive()) {
            sub.setActive(true);
            sub.setPausedReason(null);
            sub.setNextRunAt(StandingScheduler.schedule(sub).next(Instant.now(), null));
            sub.setUpdatedAt(Instant.now());
            subscriptions.save(sub);
        }
        return view(sub);
    }

    public void unsubscribe(String clientUserId, String agentId) {
        subscriptions.delete(ownSubscription(clientUserId, agentId));
    }

    /** Runs the agent now, outside the schedule. Counts toward the monthly cap like any run. */
    public RunView runNow(String clientUserId, String agentId) {
        catalog.requireOffered(agentId);
        StandingSubscription sub = ownSubscription(clientUserId, agentId);
        if (runs.existsBySubscriptionIdAndStatus(sub.getId(), StandingRun.RUNNING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A run is already in progress.");
        }
        Instant recent = Instant.now().minus(MANUAL_RUN_SPACING);
        if (runs.existsBySubscriptionIdAndTriggerAndStatusNotAndStartedAtAfter(
                sub.getId(), StandingRun.TRIGGER_MANUAL, StandingRun.SKIPPED, recent)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait a few minutes before running it again.");
        }
        String refusal = runner.refusal(sub);
        if (refusal != null) throw new ResponseStatusException(HttpStatus.CONFLICT, refusal);
        StandingRun run = runner.start(sub, Instant.now().truncatedTo(ChronoUnit.MILLIS), StandingRun.TRIGGER_MANUAL);
        if (run == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "A run is already in progress.");
        return view(run);
    }

    public List<RunView> runsFor(String clientUserId, int limit) {
        return runs.findByClientUserIdOrderByStartedAtDesc(clientUserId, PageRequest.of(0, clamp(limit, 1, 100)))
            .stream().map(this::clientRunView).toList();
    }

    // ---- client: inbox -----------------------------------------------------------------

    public List<InboxView> inboxFor(String clientUserId, String status, int limit) {
        PageRequest page = PageRequest.of(0, clamp(limit, 1, 100));
        List<InboxItem> items = status == null || status.isBlank() || status.equalsIgnoreCase("ALL")
            ? inbox.findByClientUserIdOrderByCreatedAtDesc(clientUserId, page)
            : inbox.findByClientUserIdAndStatusOrderByCreatedAtDesc(clientUserId, status.toUpperCase(), page);
        return items.stream().map(this::view).toList();
    }

    public InboxView inboxItem(String clientUserId, String itemId) {
        return view(ownItem(clientUserId, itemId));
    }

    public InboxView decide(String clientUserId, String itemId, boolean approve, String feedback) {
        InboxItem item = ownItem(clientUserId, itemId);
        if (!InboxItem.PENDING.equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item was already " + item.getStatus().toLowerCase() + ".");
        }
        if (feedback != null && feedback.length() > OptionValues.MAX_TEXT) throw badRequest("Feedback is too long.");
        item.setStatus(approve ? InboxItem.APPROVED : InboxItem.REJECTED);
        item.setFeedback(feedback == null || feedback.isBlank() ? null : feedback.trim());
        item.setDecidedAt(Instant.now());
        return view(inbox.save(item));
    }

    public long pendingCount(String clientUserId) {
        return inbox.countByClientUserIdAndStatus(clientUserId, InboxItem.PENDING);
    }

    // ---- admin -------------------------------------------------------------------------

    public record AdminOffer(String agentId, String displayName, String description, boolean availableForLiveView,
                             boolean enabled, List<AgentCustomOption> options, Map<String, String> defaultOptions,
                             List<String> lockedOptions, List<Cadence> agentCadences, List<Cadence> allowedCadences,
                             Integer maxRunsPerMonth, Long monthlyBudgetMicros, long subscribers, Instant updatedAt) {}

    public record OfferUpdate(boolean enabled, Map<String, String> defaultOptions, List<String> lockedOptions,
                              List<Cadence> allowedCadences, Integer maxRunsPerMonth, Long monthlyBudgetMicros) {}

    public record AdminSubscription(String id, String clientUserId, String clientLabel, String agentId, boolean active,
                                    String pausedReason, String cadence, Instant nextRunAt, Instant lastRunAt) {}

    public record AdminRun(RunView run, String clientUserId, String clientLabel) {}

    public List<AdminOffer> adminOffers(java.util.function.Predicate<String> availableForLiveView) {
        List<StandingSubscription> all = subscriptions.findAll();
        return catalog.all().stream().map(agent -> {
            StandingAgentOffer offer = catalog.offer(agent.agentId());
            CoreAgent meta = catalog.describe(agent.agentId()).orElse(null);
            return new AdminOffer(agent.agentId(),
                meta == null ? agent.agentId() : meta.displayName(),
                meta == null ? null : meta.description(),
                availableForLiveView.test(agent.agentId()),
                offer.isEnabled(), agent.subscriptionOptions(), catalog.adminDefaults(offer),
                List.copyOf(catalog.locked(offer)), List.copyOf(agent.allowedCadences()),
                List.copyOf(catalog.cadences(agent, offer)), offer.getMaxRunsPerMonth(), offer.getMonthlyBudgetMicros(),
                all.stream().filter(s -> s.getAgentId().equals(agent.agentId())).count(), offer.getUpdatedAt());
        }).toList();
    }

    public void updateOffer(String agentId, OfferUpdate req) {
        StandingAgent agent = catalog.require(agentId);
        Map<String, String> defaults = new HashMap<>();
        if (req.defaultOptions() != null) {
            for (AgentCustomOption o : agent.subscriptionOptions()) {
                if (!req.defaultOptions().containsKey(o.key())) continue;
                try {
                    defaults.put(o.key(), OptionValues.normalize(o, req.defaultOptions().get(o.key())));
                } catch (IllegalArgumentException e) {
                    throw badRequest(e.getMessage());
                }
            }
        }
        List<String> declaredKeys = agent.subscriptionOptions().stream().map(AgentCustomOption::key).toList();
        List<String> locked = req.lockedOptions() == null ? List.of()
            : req.lockedOptions().stream().filter(declaredKeys::contains).distinct().toList();
        List<Cadence> cadences = req.allowedCadences() == null ? List.of()
            : req.allowedCadences().stream().filter(Objects::nonNull).filter(agent.allowedCadences()::contains).distinct().toList();
        if (req.allowedCadences() != null && !req.allowedCadences().isEmpty() && cadences.isEmpty()) {
            throw badRequest("None of those frequencies is supported by this agent.");
        }
        if (req.maxRunsPerMonth() != null && req.maxRunsPerMonth() < 0) throw badRequest("The monthly run limit cannot be negative.");
        if (req.monthlyBudgetMicros() != null && req.monthlyBudgetMicros() < 0) throw badRequest("The monthly budget cannot be negative.");

        StandingAgentOffer offer = catalog.offer(agentId);
        offer.setEnabled(req.enabled());
        offer.setDefaultOptionsJson(catalog.writeMap(defaults));
        offer.setLockedOptions(String.join(",", locked));
        offer.setAllowedCadences(cadences.isEmpty() ? null : String.join(",", cadences.stream().map(Enum::name).toList()));
        offer.setMaxRunsPerMonth(req.maxRunsPerMonth());
        offer.setMonthlyBudgetMicros(req.monthlyBudgetMicros());
        offer.setUpdatedAt(Instant.now());
        offers.save(offer);
    }

    public List<AdminSubscription> adminSubscriptions(int limit) {
        List<StandingSubscription> subs = subscriptions.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, clamp(limit, 1, 500)));
        Map<String, String> labels = clientLabels(subs.stream().map(StandingSubscription::getClientUserId).toList());
        return subs.stream().map(s -> new AdminSubscription(s.getId(), s.getClientUserId(),
            labels.getOrDefault(s.getClientUserId(), s.getClientUserId()), s.getAgentId(), s.isActive(), s.getPausedReason(),
            s.getCadence(), s.getNextRunAt(), s.getLastRunAt())).toList();
    }

    public void adminSetPaused(String subscriptionId, boolean paused) {
        StandingSubscription sub = subscriptions.findById(subscriptionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such subscription"));
        sub.setActive(!paused);
        sub.setPausedReason(paused ? StandingSubscription.PAUSED_BY_ADMIN : null);
        sub.setNextRunAt(paused ? null : StandingScheduler.schedule(sub).next(Instant.now(), null));
        sub.setUpdatedAt(Instant.now());
        subscriptions.save(sub);
    }

    public List<AdminRun> adminRuns(int days, int limit) {
        Instant since = Instant.now().minus(clamp(days, 1, 366), ChronoUnit.DAYS);
        List<StandingRun> list = runs.findByStartedAtGreaterThanEqualOrderByStartedAtDesc(since, PageRequest.of(0, clamp(limit, 1, 500)));
        Map<String, String> labels = clientLabels(list.stream().map(StandingRun::getClientUserId).toList());
        return list.stream().map(r -> new AdminRun(view(r), r.getClientUserId(),
            labels.getOrDefault(r.getClientUserId(), r.getClientUserId()))).toList();
    }

    // ---- helpers -----------------------------------------------------------------------

    private StandingSubscription ownSubscription(String clientUserId, String agentId) {
        return subscriptions.findByClientUserIdAndAgentId(clientUserId, agentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You are not subscribed to this agent."));
    }

    private InboxItem ownItem(String clientUserId, String itemId) {
        return inbox.findByIdAndClientUserId(itemId, clientUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such inbox item"));
    }

    private SubscriptionView view(StandingSubscription s) {
        StandingAgent agent = catalog.require(s.getAgentId());
        return view(s, agent, catalog.offer(s.getAgentId()));
    }

    private SubscriptionView view(StandingSubscription s, StandingAgent agent, StandingAgentOffer offer) {
        Map<String, String> effective;
        try {
            effective = catalog.effectiveOptions(agent, offer, catalog.readMap(s.getOptionsJson()));
        } catch (IllegalArgumentException e) {
            // An admin narrowed an option after the client saved it; show the stored values as-is.
            effective = catalog.readMap(s.getOptionsJson());
        }
        long used = runs.countBySubscriptionIdAndStatusInAndStartedAtGreaterThanEqual(
            s.getId(), List.of(StandingRun.RUNNING, StandingRun.SUCCEEDED), StandingRunner.monthStart());
        return new SubscriptionView(s.getId(), s.getAgentId(), s.isActive(), s.getPausedReason(), Cadence.valueOf(s.getCadence()),
            s.getDayOfWeek() == null ? 1 : s.getDayOfWeek(), s.getDayOfMonth() == null ? 1 : s.getDayOfMonth(),
            s.getTimeOfDay(), s.getTimezone(), effective, s.getNextRunAt(), s.getLastRunAt(), used);
    }

    private RunView view(StandingRun r) {
        return new RunView(r.getId(), r.getAgentId(), catalog.displayName(r.getAgentId()), r.getTrigger(), r.getStatus(),
            r.getSlot(), r.getStartedAt(), r.getFinishedAt(), r.getItemsCreated(), r.getCostMicros(), r.getNote(), r.getError());
    }

    /** A client sees whether a run failed, not the internal error or what it cost the tenant. */
    private RunView clientRunView(StandingRun r) {
        return new RunView(r.getId(), r.getAgentId(), catalog.displayName(r.getAgentId()), r.getTrigger(), r.getStatus(),
            r.getSlot(), r.getStartedAt(), r.getFinishedAt(), r.getItemsCreated(), 0, r.getNote(),
            StandingRun.FAILED.equals(r.getStatus()) ? "The run did not complete." : null);
    }

    private InboxView view(InboxItem i) {
        Object payload = null;
        if (i.getPayloadJson() != null) {
            try {
                payload = objectMapper.readValue(i.getPayloadJson(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                // A malformed payload still shows the item's body.
            }
        }
        return new InboxView(i.getId(), i.getAgentId(), catalog.displayName(i.getAgentId()), i.getKind(), i.getTitle(),
            i.getSummary(), i.getBody(), payload, i.getStatus(), i.getFeedback(), i.getCreatedAt(), i.getDecidedAt());
    }

    private Map<String, String> clientLabels(List<String> ids) {
        Map<String, String> labels = new HashMap<>();
        clientUsers.findAllById(ids.stream().distinct().toList()).forEach(c -> labels.put(c.getId(), c.getEmail()));
        return labels;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
