package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A node within a PageFlow's flow chart. nextPageId/previousPageId/errorPageId are
 * nullable targets within the same PageFlow, drawn as arrows on the builder canvas.
 * canvasX/canvasY persist the node's dragged position.
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

    @Column(name = "layout_key", nullable = false, length = 64)
    private String layoutKey = "SINGLE_COLUMN";

    @Column(name = "next_page_id", length = 36)
    private String nextPageId;

    @Column(name = "previous_page_id", length = 36)
    private String previousPageId;

    @Column(name = "error_page_id", length = 36)
    private String errorPageId;

    @Column(name = "canvas_x", nullable = false)
    private double canvasX = 0;

    @Column(name = "canvas_y", nullable = false)
    private double canvasY = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
