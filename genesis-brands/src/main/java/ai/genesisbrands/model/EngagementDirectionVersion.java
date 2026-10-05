package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only archive of a direction's DirectionOutput each time it's about to be replaced
 * by an approved regeneration (see AgentRegenerationService.approve). "Current" is always
 * whatever is live in Engagement.resultsJson — rows here are only ever the versions that
 * got superseded, so nothing is lost when a client approves a regenerated asset.
 */
@Entity
@Table(name = "engagement_direction_versions")
@Data
@NoArgsConstructor
public class EngagementDirectionVersion {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "engagement_id", length = 36, nullable = false)
    private String engagementId;

    @Column(nullable = false)
    private String direction;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "direction_output_json", columnDefinition = "TEXT", nullable = false)
    private String directionOutputJson;

    @Column(name = "created_by_job_id", length = 36)
    private String createdByJobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
