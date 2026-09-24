package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Append-only record of one FlowSession traversal step (an outcome resolved and applied). */
@Entity
@Table(name = "flow_session_events")
@Data
@NoArgsConstructor
public class FlowSessionEvent {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "flow_session_token", nullable = false, length = 36)
    private String flowSessionToken;

    @Column(name = "source_page_id", nullable = false, length = 36)
    private String sourcePageId;

    @Column(name = "outcome_key", nullable = false, length = 64)
    private String outcomeKey;

    @Column(name = "target_kind", nullable = false, length = 16)
    private String targetKind;

    @Column(name = "target_page_id", length = 36)
    private String targetPageId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();
}
