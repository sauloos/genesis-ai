package ai.genesisbrands.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One metered model call: what ran, for whom, and what it cost. Append-only. */
@Entity
@Table(name = "usage_events", indexes = {
    @Index(name = "idx_usage_events_occurred_at", columnList = "occurred_at"),
    @Index(name = "idx_usage_events_client", columnList = "client_user_id, occurred_at")
})
@Data
@NoArgsConstructor
public class UsageEvent {

    public enum Kind { CHAT, IMAGE, EMBEDDING }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    @Column(length = 80)
    private String model;

    @Column(name = "input_tokens")
    private long inputTokens;

    @Column(name = "output_tokens")
    private long outputTokens;

    private int images;

    /** Provider list price in millionths of a US dollar. */
    @Column(name = "cost_micros")
    private long costMicros;

    @Column(name = "client_user_id", length = 36)
    private String clientUserId;

    @Column(name = "agent_id", length = 80)
    private String agentId;

    /** Where the call came from: the request path, or a background source such as "standing". */
    @Column(length = 160)
    private String source;

    @Column(name = "run_id", length = 36)
    private String runId;
}
