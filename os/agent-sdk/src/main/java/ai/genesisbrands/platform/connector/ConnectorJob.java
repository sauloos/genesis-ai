package ai.genesisbrands.platform.connector;

import java.time.Instant;

/**
 * A connector-visible job snapshot. {@code status} is the provider's own status name (e.g.
 * QUEUED, RUNNING, AWAITING_APPROVAL, APPROVED, REJECTED, FAILED); {@code draftOutput} is the
 * draft awaiting the client's decision, when there is one.
 */
public record ConnectorJob(
    String jobId,
    String agentId,
    String status,
    String error,
    String draftOutput,
    Instant createdAt,
    Instant completedAt
) {}
