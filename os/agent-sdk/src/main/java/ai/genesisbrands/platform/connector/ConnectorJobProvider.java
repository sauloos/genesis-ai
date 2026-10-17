package ai.genesisbrands.platform.connector;

import java.util.Optional;

/**
 * Extension point: lets the Agent connectors (MCP + REST) run a long-running, approval-gated
 * agent — a SLOT agent — as a job: start, poll, then approve or reject the draft. The core
 * gateway owns the protocol, auth and availability gating; whoever owns the agent's storage
 * (e.g. Genesis Brands' engagement regeneration) implements this to supply the mechanics.
 * <p>
 * Every method receives the caller's {@code clientUserId}, resolved server-side from the
 * connector key — an implementation must only ever act on that client's own data and treat a
 * job owned by anyone else as nonexistent.
 */
public interface ConnectorJobProvider {

    /** True when this provider runs {@code agentId} as a job. */
    boolean supports(String agentId);

    /** Queues a new job for the client's own subject; {@code feedback} may be null. */
    ConnectorJob start(String agentId, String clientUserId, String feedback);

    /** The client's own job, or empty when it doesn't exist or belongs to someone else. */
    Optional<ConnectorJob> find(String jobId, String clientUserId);

    /** Applies the job's draft. Only valid while the job awaits approval. */
    ConnectorJob approve(String jobId, String clientUserId);

    /** Discards the job's draft, leaving the current output untouched. */
    ConnectorJob reject(String jobId, String clientUserId);
}
