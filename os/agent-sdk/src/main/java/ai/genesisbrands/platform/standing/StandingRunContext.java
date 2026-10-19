package ai.genesisbrands.platform.standing;

import java.time.Instant;
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
 * @param model         the agent's own model (its {@code agent.yml} config), metered against
 *                      this run
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
    Model model
) {

    public String option(String key) {
        return options == null ? null : options.get(key);
    }

    /** A past inbox item and what the client did with it. */
    public record Decision(String title, String status, String feedback, Instant decidedAt) {}

    /** One completion with the agent's configured model, system prompt and RAG settings. */
    @FunctionalInterface
    public interface Model {
        String complete(String userPrompt);
    }
}
