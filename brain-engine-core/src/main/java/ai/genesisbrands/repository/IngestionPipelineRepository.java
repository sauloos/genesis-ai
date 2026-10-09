package ai.genesisbrands.repository;

import ai.genesisbrands.model.IngestionPipeline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IngestionPipelineRepository extends JpaRepository<IngestionPipeline, String> {

    List<IngestionPipeline> findAllByOrderByCreatedAtDesc();

    List<IngestionPipeline> findAllByActiveTrueAndNextRunAtIsNotNullAndNextRunAtBefore(Instant threshold);
}
