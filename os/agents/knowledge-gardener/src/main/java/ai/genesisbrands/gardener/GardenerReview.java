package ai.genesisbrands.gardener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One source the gardener read: whether the review worked and what it found. */
@Entity
@Table(name = "gardener_reviews")
@Data
@NoArgsConstructor
public class GardenerReview {

    public static final String REVIEWED = "REVIEWED";
    public static final String FAILED = "FAILED";

    @Id
    @Column(length = 36)
    private String id;

    /** The ingestion event that brought the source in; null when an admin asked for the review. */
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "source_id", nullable = false, length = 64)
    private String sourceId;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private int findings;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;
}
