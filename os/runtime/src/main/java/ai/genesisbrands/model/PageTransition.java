package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single named edge out of a Page: (sourcePageId, outcomeKey) -> a target Page or
 * the flow's End anchor. outcomeKey is either "error" (reserved) or a key declared by
 * one of the source Page's placed widgets' WidgetDescriptor.outcomes(). Replaces the
 * old fixed nextPageId/errorPageId/endsFlow columns with a generic, widget-driven edge
 * per outcome — one row per (sourcePageId, outcomeKey).
 */
@Entity
@Table(name = "page_transitions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"source_page_id", "outcome_key"}))
@Data
@NoArgsConstructor
public class PageTransition {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "page_flow_id", nullable = false, length = 36)
    private String pageFlowId;

    @Column(name = "source_page_id", nullable = false, length = 36)
    private String sourcePageId;

    @Column(name = "outcome_key", nullable = false, length = 64)
    private String outcomeKey;

    /** "PAGE" (targetPageId set) or "FLOW_END" (targetPageId null). */
    @Column(name = "target_kind", nullable = false, length = 16)
    private String targetKind = "PAGE";

    @Column(name = "target_page_id", length = 36)
    private String targetPageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
