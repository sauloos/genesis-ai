package ai.genesisbrands.platform.standing;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Everything one standing run may use. The client identity and context are resolved by the
 * runtime from the database for this run, never from anything a request supplied.
 *
 * @param options       the subscription's effective options (admin-locked values already
 *                      applied), keyed by {@code AgentCustomOption.key}
 * @param clientContext markdown describing the client (brand, deliverables, recent activity),
 *                      assembled from every {@link StandingContextContributor}; may be empty
 * @param recentDecisions the client's latest approvals and rejections of this agent's items,
 *                      newest first, so the agent learns what this client accepts
 * @param nextRunAt     when the subscription runs next (null when it has no schedule), so an
 *                      agent can spread what it plans across the period it covers
 * @param zone          the client's time zone for this subscription
 * @param model         the agent's own model (its {@code agent.yml} config), metered against
 *                      this run
 * @param memory        what this agent kept for this subscription after its last successful
 *                      run ({@link StandingRunResult#memory()}), or null on the first run
 * @param event         what started an {@link TriggerKind#EVENT} run (a lead, an RFP), or null
 *                      for a scheduled or manual run
 */
public record StandingRunContext(
    String runId,
    String subscriptionId,
    String clientUserId,
    String clientName,
    Map<String, String> options,
    String clientContext,
    List<Decision> recentDecisions,
    Instant previousRunAt,
    Instant now,
    Instant nextRunAt,
    ZoneId zone,
    Model model,
    String memory,
    Event event
) {

    /** A context without an event, for scheduled runs (and tests). */
    public StandingRunContext(String runId, String subscriptionId, String clientUserId, String clientName,
                              Map<String, String> options, String clientContext, List<Decision> recentDecisions,
                              Instant previousRunAt, Instant now, Instant nextRunAt, ZoneId zone, Model model, String memory) {
        this(runId, subscriptionId, clientUserId, clientName, options, clientContext, recentDecisions,
            previousRunAt, now, nextRunAt, zone, model, memory, null);
    }

    /** A context without memory, for agents (and tests) that keep none. */
    public StandingRunContext(String runId, String subscriptionId, String clientUserId, String clientName,
                              Map<String, String> options, String clientContext, List<Decision> recentDecisions,
                              Instant previousRunAt, Instant now, Instant nextRunAt, ZoneId zone, Model model) {
        this(runId, subscriptionId, clientUserId, clientName, options, clientContext, recentDecisions,
            previousRunAt, now, nextRunAt, zone, model, null, null);
    }

    public String option(String key) {
        return options == null ? null : options.get(key);
    }

    /**
     * What a client (or their own system, through a connector) sent an event-triggered agent.
     * The runtime has already kept only the agent's declared {@link StandingAgent#eventFields()}
     * and capped their sizes; the values are still untrusted text from outside.
     *
     * @param source where it came from: {@code inbox} (the client's app), {@code connector}
     */
    public record Event(String source, Instant receivedAt, Map<String, String> fields) {

        public String field(String key) {
            return fields == null ? null : fields.get(key);
        }
    }

    /** A past inbox item and what the client did with it. */
    public record Decision(String title, String status, String feedback, Instant decidedAt) {}

    /** One completion with the agent's configured model, system prompt and RAG settings. */
    @FunctionalInterface
    public interface Model {
        String complete(String userPrompt);
    }
}
