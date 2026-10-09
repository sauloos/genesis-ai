package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "ingestion_events")
@Data
@NoArgsConstructor
public class IngestionEvent {

    public enum Kind {
        SINGLE_URL, SINGLE_FILE, SINGLE_YOUTUBE, SINGLE_VIMEO,
        BLOG_CRAWL, YOUTUBE_CHANNEL
    }

    public enum Status { RUNNING, SUCCESS, FAILED, PARTIAL }

    @Id
    @Column(length = 36)
    private String id;

    // Null for one-off "Ingest Item" runs not tied to a pipeline
    @Column(name = "pipeline_id", length = 36)
    private String pipelineId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "source_label", columnDefinition = "TEXT")
    private String sourceLabel;

    @Column(length = 10)
    private String layer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "items_found")
    private int itemsFound;

    @Column(name = "items_ingested")
    private int itemsIngested;

    @Column(name = "items_skipped")
    private int itemsSkipped;

    @Column(name = "items_failed")
    private int itemsFailed;

    @Column(name = "log_output", columnDefinition = "TEXT")
    private String logOutput;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
