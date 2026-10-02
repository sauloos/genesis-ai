package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "agent_regeneration_jobs")
@Data
@NoArgsConstructor
public class AgentRegenerationJob {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "engagement_id", length = 36, nullable = false)
    private String engagementId;

    @Column(nullable = false)
    private String direction;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    /** SLOT (default): overwrites the agent's one canonical output for this
     *  (engagementId, direction, agentId). CREATE: appends a new asset instance instead —
     *  for future non-cascade-coupled agents (flyers, banners, signage). No CREATE-mode
     *  agent exists yet; the column exists now so it doesn't need a breaking migration later. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Mode mode = Mode.SLOT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public enum Mode { SLOT, CREATE }
    public enum Status { QUEUED, RUNNING, DONE, FAILED }
}
