package ai.genesisbrands.service;

import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fails engagements whose pipeline died with its process. A deploy or crash mid-run leaves the
 * row PENDING/RUNNING with nothing behind it, which the client sees as an endless spinner and the
 * admin rerun refuses. A full run takes about 30 minutes, so the default timeout is 90.
 *
 * <p>A time threshold, not a startup sweep, because several replicas run at once and a restarting
 * one must not fail a run another replica is still working on.
 */
@Component
public class EngagementStaleRunSweeper {

    private static final Logger log = LoggerFactory.getLogger(EngagementStaleRunSweeper.class);
    static final String MESSAGE = "The run was interrupted (the service restarted or the run timed out) before it finished. "
        + "Run it again.";

    static final List<Engagement.Status> ACTIVE = List.of(Engagement.Status.PENDING, Engagement.Status.RUNNING);

    private final EngagementRepository repo;
    private final Duration timeout;

    public EngagementStaleRunSweeper(EngagementRepository repo,
                                     @Value("${genesis.engagements.stale-run-minutes:90}") long minutes) {
        this.repo = repo;
        this.timeout = Duration.ofMinutes(Math.max(minutes, 1));
    }

    @Scheduled(fixedDelayString = "${genesis.engagements.stale-sweep-ms:300000}", initialDelayString = "${genesis.engagements.stale-sweep-ms:300000}")
    public void sweep() {
        sweep(Instant.now());
    }

    int sweep(Instant now) {
        Instant cutoff = now.minus(timeout);
        List<String> ids = repo.findStaleRunIds(ACTIVE, cutoff);
        int failed = 0;
        for (String id : ids) {
            try {
                if (repo.failIfStale(id, Engagement.Status.FAILED, ACTIVE, MESSAGE, now, cutoff) > 0) {
                    failed++;
                    log.warn("Engagement {} had no progress since before {}; marked FAILED", id, cutoff);
                }
            } catch (Exception e) {
                log.warn("Could not sweep engagement {}: {}", id, e.getMessage());
            }
        }
        return failed;
    }
}
