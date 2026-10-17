package ai.genesisbrands.repository;

import ai.genesisbrands.model.TrainingContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrainingContentRepository extends JpaRepository<TrainingContent, String> {
    List<TrainingContent> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    void deleteBySessionId(String sessionId);
}
