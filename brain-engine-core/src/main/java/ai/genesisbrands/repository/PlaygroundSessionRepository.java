package ai.genesisbrands.repository;

import ai.genesisbrands.model.PlaygroundSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlaygroundSessionRepository extends JpaRepository<PlaygroundSession, String> {
    List<PlaygroundSession> findAllByAgentIdOrderByCreatedAtDesc(String agentId);
}
