package ai.genesisbrands.standing;

import ai.genesisbrands.platform.standing.Cadence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Polls for due subscriptions and starts their runs. Safe with several replicas: a slot is
 * claimed by a conditional update of {@code nextRunAt} (only one replica's update matches),
 * and the run row's unique {@code (subscription, slot)} backs that up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StandingScheduler {

    /** Runs still RUNNING after this long were cut off by a restart; they are failed. */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    static final int BATCH = 20;

    private final StandingSubscriptionRepository subscriptions;
    private final StandingRunRepository runs;
    private final StandingRunner runner;

    @Scheduled(fixedDelayString = "${genesis.standing.poll-ms:60000}", initialDelayString = "${genesis.standing.initial-delay-ms:30000}")
    public void tick() {
        Instant now = Instant.now();
        failStaleRuns(now);
        for (StandingSubscription sub : subscriptions.findByActiveTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now, PageRequest.of(0, BATCH))) {
            try {
                Instant slot = sub.getNextRunAt();
                Instant next = schedule(sub).next(now, slot);
                if (subscriptions.claim(sub.getId(), slot, next, now) == 1) {
                    runner.start(sub, slot, StandingRun.TRIGGER_SCHEDULE);
                }
            } catch (RuntimeException e) {
                log.warn("Could not start standing subscription {}: {}", sub.getId(), e.getMessage());
            }
        }
    }

    private void failStaleRuns(Instant now) {
        for (StandingRun run : runs.findByStatusAndStartedAtLessThan(StandingRun.RUNNING, now.minus(STALE_AFTER))) {
            run.setStatus(StandingRun.FAILED);
            run.setError("Interrupted (the service restarted while the run was in progress).");
            run.setFinishedAt(now);
            runs.save(run);
        }
    }

    static StandingSchedule schedule(StandingSubscription sub) {
        return new StandingSchedule(
            Cadence.valueOf(sub.getCadence()),
            sub.getDayOfWeek() == null ? DayOfWeek.MONDAY.getValue() : sub.getDayOfWeek(),
            sub.getDayOfMonth() == null ? 1 : sub.getDayOfMonth(),
            LocalTime.parse(sub.getTimeOfDay()),
            ZoneId.of(sub.getTimezone()));
    }
}
