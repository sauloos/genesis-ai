package ai.genesisbrands.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extend, don't copy: no module may ship a static agent-view or widget file at a path another
 * module already ships — a same-path file silently replaces the other one (whichever jar wins
 * the classpath). Change another module's view with an extension instead.
 */
class ModuleStaticPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"static/agent-views/", "static/widgets/"})
    void noStaticFileIsShippedByTwoModules(String root) throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:" + root + "**/*.*");
        Map<String, List<String>> byPath = new TreeMap<>();
        for (Resource r : files) {
            String url = r.getURL().toString();
            String path = url.substring(url.lastIndexOf(root));
            byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(url);
        }
        assertThat(byPath).isNotEmpty();
        assertThat(byPath).allSatisfy((path, sources) -> assertThat(sources).as(path).hasSize(1));
    }
}
