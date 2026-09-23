package ai.genesisbrands.repository;

import ai.genesisbrands.model.PageFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageFlowRepository extends JpaRepository<PageFlow, String> {

    List<PageFlow> findBySlug(String slug);

    Optional<PageFlow> findByLiveTrueAndSlug(String slug);

    @Modifying
    @Query("UPDATE PageFlow f SET f.live = false WHERE f.slug = :slug")
    void deactivateAllForSlug(@Param("slug") String slug);
}
