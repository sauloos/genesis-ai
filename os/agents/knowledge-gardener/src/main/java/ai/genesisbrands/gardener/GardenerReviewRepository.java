package ai.genesisbrands.gardener;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GardenerReviewRepository extends JpaRepository<GardenerReview, String> {

    List<GardenerReview> findAllByOrderByReviewedAtDesc(Pageable page);

    boolean existsBySourceIdAndStatus(String sourceId, String status);
}
