package ai.genesisbrands.repository;

import ai.genesisbrands.model.BrandBookTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrandBookTemplateRepository extends JpaRepository<BrandBookTemplate, String> {

    List<BrandBookTemplate> findAllByOrderByCreatedAtDesc();

    List<BrandBookTemplate> findByStatusOrderByCreatedAtDesc(BrandBookTemplate.Status status);

    Optional<BrandBookTemplate> findFirstByStatusOrderByCreatedAtDesc(BrandBookTemplate.Status status);
}
