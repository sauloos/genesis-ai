package ai.genesisbrands.platform;

/**
 * Extension point: wraps an agent's view module instead of replacing it. A module that
 * wants to change how another module's agent view looks or behaves (e.g. a tenant giving
 * the core Consultant chat its own copy) registers one of these beans and ships the
 * extension module; it never ships a file at the base view's own path.
 * <p>
 * The client loader ({@code /widgets/agent-loader.js}) chains every extension for an
 * {@code (agentId, view)} pair, lowest {@link #order()} innermost. An extension module
 * exports {@code mount(container, agent, ctx, base)}, where {@code base(container, agent,
 * ctx)} mounts the layer beneath it — typically called with an enriched {@code ctx}. An
 * optional stylesheet next to the module (same name, {@code .css}) is loaded alongside.
 */
public interface AgentViewExtension {

    /** The agent whose view this extends. */
    String agentId();

    /** {@code "live"} or {@code "playground"}. */
    String view();

    /** Absolute URL path of the extension's ES module, e.g. {@code /agent-views/consultant/ext/brands-live.js}. */
    String module();

    /** Chain position: lower wraps closer to the base view. */
    default int order() {
        return 0;
    }
}
