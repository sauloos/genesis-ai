package ai.genesisbrands.publishing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Publishes due jobs. Safe with several replicas: a job is claimed by a conditional update
 * (QUEUED to RUNNING) that only one replica wins. A job left RUNNING by a restart is failed
 * rather than retried, because the platform may already have published it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishScheduler {

    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    static final int BATCH = 10;

    private final PublishJobRepository jobs;
    private final PublishingService publishing;

    @Scheduled(fixedDelayString = "${genesis.publishing.poll-ms:60000}", initialDelayString = "${genesis.publishing.initial-delay-ms:45000}")
    public void tick() {
        if (!publishing.ready()) return;
        Instant now = Instant.now();
        publishing.expireAccounts(now);
        for (PublishJob stale : jobs.findByStatusAndStartedAtLessThan(PublishJob.RUNNING, now.minus(STALE_AFTER))) {
            stale.setStatus(PublishJob.FAILED);
            stale.setError("Interrupted while publishing. Check the account before trying again: the post may already be live.");
            stale.setFinishedAt(now);
            jobs.save(stale);
        }
        for (PublishJob job : jobs.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(PublishJob.QUEUED, now, PageRequest.of(0, BATCH))) {
            try {
                if (jobs.claim(job.getId(), Instant.now()) != 1) continue;
                PublishJob claimed = jobs.findById(job.getId()).orElse(null);
                if (claimed != null) publishing.run(claimed);
            } catch (RuntimeException e) {
                log.warn("Could not publish job {}: {}", job.getId(), e.getMessage());
            }
        }
    }
}
