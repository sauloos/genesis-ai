package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "ingestion_pipelines")
@Data
@NoArgsConstructor
public class IngestionPipeline {

    public enum Type { BLOG_CRAWL, YOUTUBE_CHANNEL }

    public enum Schedule { MANUAL, DAILY, WEEKLY, MONTHLY }

    @Id
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "target_url", nullable = false, columnDefinition = "TEXT")
    private String targetUrl;

    @Column(nullable = false, length = 10)
    private String layer;

    @Column(name = "content_category", length = 30)
    private String contentCategory;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Schedule schedule;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
