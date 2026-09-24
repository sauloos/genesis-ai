package ai.genesisbrands.repository;

import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.platform.PageFlowRouting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PageFlowRepository extends JpaRepository<PageFlow, String> {

    List<PageFlow> findBySlug(String slug);

    List<PageFlow> findAllByLiveTrueAndSlug(String slug);

    List<PageFlow> findAllByLiveTrue();

    /**
     * Resolves the one live PageFlow at a given root prefix + slug. Two live flows can
     * legitimately share a slug under different root prefixes (see
     * PageFlowService#setLive), so this disambiguates in Java via PageFlowRouting's own
     * routeKey rather than a raw rootPrefix-equality predicate — a JPQL "= :rootPrefix"
     * would silently never match a null-prefixed (default "/live") row.
     */
    default Optional<PageFlow> findLiveByRoute(String rootPrefix, String slug) {
        String key = PageFlowRouting.routeKey(rootPrefix, slug);
        return findAllByLiveTrueAndSlug(slug).stream()
            .filter(f -> PageFlowRouting.routeKey(f.getRootPrefix(), f.getSlug()).equals(key))
            .findFirst();
    }
}
