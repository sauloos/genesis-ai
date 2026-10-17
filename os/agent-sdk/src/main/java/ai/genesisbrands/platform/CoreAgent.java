package ai.genesisbrands.platform;

import java.util.List;

/**
 * Extension point: a tenant registers a specialist agent so Playground can discover it
 * generically. This is a registry entry, not an invocation contract — each agent's real
 * request/response shape stays whatever the tenant's own controller defines; testEndpoint()
 * just tells the caller where to POST a test run.
 */
public interface CoreAgent {

    String agentId();

    String displayName();

    String description();

    String testEndpoint();

    /**
     * Icon identifier for this agent's card (dashboard, playground, admin). Null means the
     * host renders its own fallback glyph.
     */
    default String icon() {
        return null;
    }

    /**
     * Declares whether this agent ships its own Live Dashboard view module at
     * {@code /agent-views/<agentId>/live.js}, resolved by convention — not probed at
     * runtime. False means the agent's card (if shown at all on the live dashboard) is not
     * clickable.
     */
    default boolean hasLiveView() {
        return false;
    }

    default boolean requiresQuestionnaire() {
        return false;
    }

    default boolean supportsPlayground() {
        return true;
    }

    default boolean supportsABCompare() {
        return false;
    }

    /**
     * True for an agent that can expose tool-use capabilities (e.g. Consultant's ability to
     * trigger regeneration of other agents' output). Admin-gated via
     * AgentCatalogConfig.toolsEnabled — this flag only declares that the capability exists to
     * be turned on, mirroring supportsABCompare().
     */
    default boolean supportsToolUse() {
        return false;
    }

    default List<AgentCustomOption> customOptions() {
        return List.of();
    }

    /**
     * True for an agent whose real interaction is a stateful, multi-turn chat (e.g.
     * Consultant) rather than a single brief-in/result-out execution. Playground uses
     * this to skip its DirectionBrief-based run form for the agent, since testEndpoint()
     * has no single-shot execute contract to call.
     */
    default boolean chatBased() {
        return false;
    }

    /**
     * False for an agent whose Live Dashboard card should be clickable even with no
     * owning, finished Engagement (e.g. a generic utility agent like a summarizer or
     * FAQ bot) — true (the default) preserves today's behavior for every existing
     * specialist, which needs a chosen-direction brief to act on.
     */
    default boolean requiresEngagementContext() {
        return true;
    }

    /**
     * Declares whether this agent ships its own Playground view module at
     * {@code /agent-views/<agentId>/playground.js}, resolved by convention like
     * {@link #hasLiveView()}'s {@code live.js}. False (the default) keeps every existing
     * agent on Playground's current DirectionBrief-form/chat-iframe handling.
     */
    default boolean hasPlaygroundView() {
        return false;
    }
}
