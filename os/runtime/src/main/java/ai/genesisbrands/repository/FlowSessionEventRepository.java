package ai.genesisbrands.repository;

import ai.genesisbrands.model.FlowSessionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlowSessionEventRepository extends JpaRepository<FlowSessionEvent, String> {
    List<FlowSessionEvent> findByFlowSessionTokenOrderByOccurredAtAsc(String flowSessionToken);
}
