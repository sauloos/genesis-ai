package ai.genesisbrands.publishing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Reads the figures of recently published posts (reactions, comments, reach), so agents can
 * learn what performs. A post is read again every {@code refresh-hours} for {@code days} after
 * it went out, a batch per tick. Inert until publishing is set up. Platform reads are free.
 */
@Component
public class PostMetricsScheduler {

    private final PublishJobRepository jobs;
    private final PublishingService publishing;
    private final Duration refresh;
    private final Duration window;
    private final int batch;

    public PostMetricsScheduler(PublishJobRepository jobs, PublishingService publishing,
                                @Value("${genesis.publishing.metrics.refresh-hours:12}") long refreshHours,
                                @Value("${genesis.publishing.metrics.days:30}") long days,
                                @Value("${genesis.publishing.metrics.batch:25}") int batch) {
        this.jobs = jobs;
        this.publishing = publishing;
        this.refresh = Duration.ofHours(Math.max(1, refreshHours));
        this.window = Duration.ofDays(Math.max(1, days));
        this.batch = Math.max(1, Math.min(200, batch));
    }

    @Scheduled(fixedDelayString = "${genesis.publishing.metrics.poll-ms:900000}",
        initialDelayString = "${genesis.publishing.metrics.initial-delay-ms:120000}")
    public void tick() {
        if (!publishing.ready()) return;
        refreshDue(Instant.now());
    }

    void refreshDue(Instant now) {
        for (PublishJob job : jobs.findMetricsDue(now.minus(window), now.minus(refresh), PageRequest.of(0, batch))) {
            publishing.refreshMetrics(job, now);
        }
    }
}
