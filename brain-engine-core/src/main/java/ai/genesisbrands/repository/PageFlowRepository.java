package ai.genesisbrands.repository;

import ai.genesisbrands.model.PageFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PageFlowRepository extends JpaRepository<PageFlow, String> {

    List<PageFlow> findBySlug(String slug);

    Optional<PageFlow> findByLiveTrueAndSlug(String slug);

    List<PageFlow> findAllByLiveTrue();
}
