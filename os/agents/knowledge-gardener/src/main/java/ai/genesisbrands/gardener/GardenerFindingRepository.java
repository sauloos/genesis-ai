package ai.genesisbrands.gardener;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GardenerFindingRepository extends JpaRepository<GardenerFinding, String> {

    List<GardenerFinding> findAllByStatusOrderByCreatedAtDesc(String status, Pageable page);

    List<GardenerFinding> findAllByOrderByCreatedAtDesc(Pageable page);

    long countByStatus(String status);
}
