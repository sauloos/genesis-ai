package ai.genesisbrands.service;

import ai.genesisbrands.model.IngestionPipeline;
import ai.genesisbrands.repository.IngestionPipelineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionPipelineScheduler {

    private final IngestionPipelineRepository pipelineRepository;
    private final IngestionExecutionService executionService;

    @Scheduled(fixedRate = 300_000) // check every 5 minutes
    @Transactional
    public void fireDuePipelines() {
        for (IngestionPipeline p : pipelineRepository
                .findAllByActiveTrueAndNextRunAtIsNotNullAndNextRunAtBefore(Instant.now())) {
            log.info("Firing due pipeline: {} ({})", p.getName(), p.getId());
            try {
                executionService.runPipeline(p);
                p.setLastRunAt(Instant.now());
                p.setNextRunAt(computeNextRun(p));
                pipelineRepository.save(p);
            } catch (Exception e) {
                log.error("Failed to fire pipeline {}: {}", p.getId(), e.getMessage(), e);
            }
        }
    }

    public static Instant computeNextRun(IngestionPipeline p) {
        Instant base = Instant.now();
        return switch (p.getSchedule()) {
            case DAILY   -> base.plus(1,   ChronoUnit.DAYS);
            case WEEKLY  -> base.plus(7,   ChronoUnit.DAYS);
            case MONTHLY -> base.plus(30,  ChronoUnit.DAYS);
            case MANUAL  -> null;
        };
    }
}
