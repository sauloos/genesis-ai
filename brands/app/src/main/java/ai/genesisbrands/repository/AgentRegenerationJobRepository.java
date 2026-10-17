package ai.genesisbrands.repository;

import ai.genesisbrands.model.AgentRegenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRegenerationJobRepository extends JpaRepository<AgentRegenerationJob, String> {
    long countByEngagementIdAndDirectionAndAgentId(String engagementId, String direction, String agentId);
    Optional<AgentRegenerationJob> findFirstByEngagementIdAndDirectionAndStatusIn(
        String engagementId, String direction, List<AgentRegenerationJob.Status> statuses);
}
