package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A node within a PageFlow's flow chart. "Next"/"error" outgoing edges are owned by
 * PageTransition (one row per (pageId, outcomeKey), driven by the page's placed
 * widgets' declared outcomes). previousPageId remains an explicit, optional override
 * here — it isn't a widget outcome, so it doesn't fit the transition model; when null
 * it falls back to effectivePreviousPageId (computed: whichever other page's outcome
 * points here). canvasX/canvasY persist the node's dragged position.
 */
@Entity
@Table(name = "pages")
@Data
@NoArgsConstructor
public class Page {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "page_flow_id", nullable = false, length = 36)
    private String pageFlowId;

    @Column(nullable = false)
    private String name;

    @Column(name = "requires_auth", nullable = false)
    private boolean requiresAuth = false;

    @Column(name = "is_error_page", nullable = false)
    private boolean errorPage = false;

    /** Marks this page as a designated "flow ends here" page a PageFlow's endPageId can point to. */
    @Column(name = "is_end_page", nullable = false, columnDefinition = "boolean default false")
    private boolean endPage = false;

    @Column(name = "layout_key", nullable = false, length = 64)
    private String layoutKey = "SINGLE_COLUMN";

    /** Whether the page's card shell renders its border/shadow. Off for pages meant to blend into a site (e.g. a root-mounted landing page). */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean bordered = true;

    @Column(name = "previous_page_id", length = 36)
    private String previousPageId;

    @Column(name = "canvas_x", nullable = false)
    private double canvasX = 0;

    @Column(name = "canvas_y", nullable = false)
    private double canvasY = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Computed at read time: previousPageId if set, else whichever page most recently pointed an outcome here. */
    @Transient
    private String effectivePreviousPageId;

    /** Computed at read time: this page's explicit "error" PageTransition if set, else the flow's designated error page. */
    @Transient
    private String effectiveErrorPageId;
}
