package ai.genesisbrands.gardener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Where a newly ingested source disagrees with a Layer 1 module, or teaches something no module
 * covers. An admin resolves it: APPLIED once they have edited the module by hand, DISMISSED otherwise.
 */
@Entity
@Table(name = "gardener_findings")
@Data
@NoArgsConstructor
public class GardenerFinding {

    public static final String OPEN = "OPEN";
    public static final String APPLIED = "APPLIED";
    public static final String DISMISSED = "DISMISSED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "review_id", nullable = false, length = 36)
    private String reviewId;

    @Column(name = "source_id", nullable = false, length = 64)
    private String sourceId;

    @Column(name = "source_title", columnDefinition = "TEXT")
    private String sourceTitle;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    /** CONTRADICTION or GAP. */
    @Column(nullable = false, length = 16)
    private String kind;

    /** The Layer 1 module key, e.g. evaluation/copy-rubric; null for a gap no module covers. */
    @Column(length = 200)
    private String module;

    @Column(name = "module_excerpt", columnDefinition = "TEXT")
    private String moduleExcerpt;

    @Column(name = "source_excerpt", columnDefinition = "TEXT")
    private String sourceExcerpt;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(columnDefinition = "TEXT")
    private String suggestion;

    @Column(nullable = false, length = 12)
    private String status = OPEN;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
