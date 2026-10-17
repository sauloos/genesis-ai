package ai.genesisbrands.repository;

import ai.genesisbrands.model.PageTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PageTransitionRepository extends JpaRepository<PageTransition, String> {
    List<PageTransition> findByPageFlowId(String pageFlowId);
    Optional<PageTransition> findBySourcePageIdAndOutcomeKey(String sourcePageId, String outcomeKey);
    void deleteByPageFlowId(String pageFlowId);
    void deleteBySourcePageId(String sourcePageId);
    void deleteByTargetPageId(String targetPageId);
}
