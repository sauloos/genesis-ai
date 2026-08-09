package ai.genesisbrands.repository;

import ai.genesisbrands.model.Engagement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EngagementRepository extends JpaRepository<Engagement, String> {
    List<Engagement> findAllByOrderByCreatedAtDesc();
    List<Engagement> findAllBySourceOrderByCreatedAtDesc(Engagement.Source source);
}
