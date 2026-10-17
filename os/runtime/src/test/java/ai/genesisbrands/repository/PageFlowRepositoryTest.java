package ai.genesisbrands.repository;

import ai.genesisbrands.model.PageFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * findLiveByRoute is a default method covering the exact bug this test guards against:
 * two live PageFlows sharing a slug under different root prefixes (a normal, supported
 * configuration — see PageFlowService#setLive) used to make the old single-result
 * findByLiveTrueAndSlug query throw NonUniqueResultException. Disambiguation now
 * happens in Java via PageFlowRouting's routeKey instead of a raw column predicate.
 */
@ExtendWith(MockitoExtension.class)
class PageFlowRepositoryTest {

    @Mock private PageFlowRepository repo;

    private PageFlow flow(String id, String slug, String rootPrefix) {
        PageFlow f = new PageFlow();
        f.setId(id);
        f.setSlug(slug);
        f.setRootPrefix(rootPrefix);
        f.setLive(true);
        return f;
    }

    @Test
    void findLiveByRoute_disambiguatesSharedSlugByRootPrefix() {
        PageFlow liveAtDefault = flow("f1", "hello", null);
        PageFlow liveAtRoot = flow("f2", "hello", "");
        when(repo.findAllByLiveTrueAndSlug("hello")).thenReturn(List.of(liveAtDefault, liveAtRoot));
        when(repo.findLiveByRoute(anyRootPrefix(), eqSlug())).thenCallRealMethod();

        assertThat(repo.findLiveByRoute(null, "hello")).contains(liveAtDefault);
        assertThat(repo.findLiveByRoute("", "hello")).contains(liveAtRoot);
    }

    @Test
    void findLiveByRoute_noMatchingPrefixReturnsEmpty() {
        PageFlow liveAtCustomPrefix = flow("f1", "hello", "preview");
        when(repo.findAllByLiveTrueAndSlug("hello")).thenReturn(List.of(liveAtCustomPrefix));
        when(repo.findLiveByRoute(anyRootPrefix(), eqSlug())).thenCallRealMethod();

        Optional<PageFlow> result = repo.findLiveByRoute(null, "hello");

        assertThat(result).isEmpty();
    }

    private static String anyRootPrefix() {
        return org.mockito.ArgumentMatchers.nullable(String.class);
    }

    private static String eqSlug() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
