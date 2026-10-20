package ai.genesisbrands.publishing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One post to publish to one connected account at a given time. {@code sourceRef} names what
 * it came from (e.g. {@code standing-inbox:<itemId>}) so the module that scheduled it can find
 * it again; the publishing runtime never interprets it. Media is copied into the job's own
 * blob folder (as JPEG) when it is scheduled, so it survives the source changing.
 */
@Entity
@Table(name = "publish_jobs", indexes = {
    @Index(name = "idx_publish_jobs_due", columnList = "status, scheduled_at"),
    @Index(name = "idx_publish_jobs_source", columnList = "client_user_id, source_ref")})
@Data
@NoArgsConstructor
public class PublishJob {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "client_user_id", nullable = false, length = 36)
    private String clientUserId;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 40)
    private String network;

    @Column(name = "source_ref", length = 120)
    private String sourceRef;

    @Column(columnDefinition = "TEXT")
    private String text;

    /** JSON list of blob paths, in order. */
    @Column(name = "media_json", columnDefinition = "TEXT")
    private String mediaJson;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(nullable = false, length = 20)
    private String status = QUEUED;

    private int attempts;

    @Column(name = "external_post_id", length = 300)
    private String externalPostId;

    @Column(length = 1000)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** The published post's latest figures ({@code PublishChannel.PostMetrics} as JSON), or null before the first read. */
    @Column(name = "metrics_json", columnDefinition = "TEXT")
    private String metricsJson;

    /** When the figures were last read (also set when the platform had none), so reads are spaced out. */
    @Column(name = "metrics_at")
    private Instant metricsAt;
}
