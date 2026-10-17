package ai.genesisbrands.repository;

import ai.genesisbrands.model.Engagement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EngagementRepository extends JpaRepository<Engagement, String> {
    List<Engagement> findAllByOrderByCreatedAtDesc();
    List<Engagement> findAllBySourceOrderByCreatedAtDesc(Engagement.Source source);
    List<Engagement> findAllByClientUserIdOrderByCreatedAtDesc(String clientUserId);
    Optional<Engagement> findFirstByStatusOrderByCreatedAtDesc(Engagement.Status status);
    List<Engagement> findAllByStatusOrderByCreatedAtDesc(Engagement.Status status);
}
