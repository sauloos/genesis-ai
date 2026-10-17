package ai.genesisbrands.repository;

import ai.genesisbrands.model.IngestionEvent;
import ai.genesisbrands.model.IngestionEvent.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IngestionEventRepository extends JpaRepository<IngestionEvent, String> {

    List<IngestionEvent> findTop20ByOrderByStartedAtDesc();

    List<IngestionEvent> findAllByPipelineIdOrderByStartedAtDesc(String pipelineId);

    List<IngestionEvent> findAllByStatus(Status status);
}
