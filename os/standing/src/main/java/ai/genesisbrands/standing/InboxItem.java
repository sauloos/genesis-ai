package ai.genesisbrands.standing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Something a standing agent produced for a client, waiting for (or past) their decision.
 * Publishing states (scheduled, published, failed) arrive with the channel phase; until then
 * an approved item is simply the client's to use.
 */
@Entity
@Table(name = "standing_inbox_items",
    indexes = @Index(name = "idx_standing_inbox_client", columnList = "client_user_id, status, created_at"))
@Data
@NoArgsConstructor
public class InboxItem {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "client_user_id", nullable = false, length = 36)
    private String clientUserId;

    @Column(name = "agent_id", nullable = false, length = 80)
    private String agentId;

    @Column(name = "subscription_id", length = 36)
    private String subscriptionId;

    @Column(name = "run_id", length = 36)
    private String runId;

    /** {@code StandingRunResult.Item} kind: REPORT, DRAFT_POST, ASSET, PROPOSAL. */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 1000)
    private String summary;

    /** Markdown. */
    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false, length = 20)
    private String status = PENDING;

    /** The client's note when deciding, fed to the agent's next runs. */
    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;
}
