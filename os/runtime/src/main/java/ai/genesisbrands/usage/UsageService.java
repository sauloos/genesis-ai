package ai.genesisbrands.usage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** The usage ledger: records metered calls and answers "how much has X spent". */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private final UsageEventRepository events;
    private final UsagePricing pricing;

    public void recordChat(String model, long inputTokens, long outputTokens, UsageScope scope, String agentHint) {
        record(UsageEvent.Kind.CHAT, model, inputTokens, outputTokens, 0, 0, 0, scope, agentHint);
    }

    /** {@code inputTokens} excludes cached tokens; the event stores the full prompt size, priced per kind. */
    public void recordChat(String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                           UsageScope scope, String agentHint) {
        record(UsageEvent.Kind.CHAT, model, inputTokens, outputTokens, 0, cacheReadTokens, cacheWriteTokens, scope, agentHint);
    }

    /** For image models, whose calls carry no token counts (the Logo agent also calls Ideogram over REST). */
    public void recordImage(String model, int images) {
        record(UsageEvent.Kind.IMAGE, model, 0, 0, images, 0, 0, UsageContext.current(), UsageContext.agentHint());
    }

    /** Never throws: metering must not break the call it measures. */
    void record(UsageEvent.Kind kind, String model, long in, long out, int images, UsageScope scope, String hint) {
        record(kind, model, in, out, images, 0, 0, scope, hint);
    }

    void record(UsageEvent.Kind kind, String model, long in, long out, int images, long cacheRead, long cacheWrite,
                UsageScope scope, String hint) {
        try {
            UsageEvent e = new UsageEvent();
            e.setId(UUID.randomUUID().toString());
            e.setOccurredAt(Instant.now());
            e.setKind(kind);
            e.setModel(model);
            e.setInputTokens(in + cacheRead + cacheWrite);
            e.setOutputTokens(out);
            e.setImages(images);
            e.setCostMicros(pricing.costMicros(model, in, out, images, cacheRead, cacheWrite));
            if (scope != null) {
                e.setClientUserId(scope.resolveClientUserId());
                e.setAgentId(scope.agentId() != null ? scope.agentId() : hint);
                e.setSource(truncate(scope.source(), 160));
                e.setRunId(scope.runId());
            } else {
                e.setAgentId(hint);
            }
            events.save(e);
        } catch (RuntimeException ex) {
            log.warn("Could not record usage for model {}: {}", model, ex.toString());
        }
    }

    public List<UsageEvent> since(Instant since) {
        return events.findByOccurredAtGreaterThanEqual(since);
    }

    public List<UsageEvent> recent(Instant since, int limit) {
        return events.findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(since, PageRequest.of(0, limit));
    }

    /** What a client has spent this calendar month (UTC), in micro-dollars. */
    public long spentThisMonthMicros(String clientUserId) {
        Instant start = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return events.sumCostMicrosForClientSince(clientUserId, start);
    }

    /** Everything metered under one run id (a standing-agent run). */
    public long costOfRunMicros(String runId) {
        return events.sumCostMicrosForRun(runId);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
