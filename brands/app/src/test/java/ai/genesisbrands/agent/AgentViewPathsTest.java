package ai.genesisbrands.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extend, don't copy: no module may ship a static agent-view file at a path another module
 * already ships — a same-path file silently replaces the other one (whichever jar wins the
 * classpath). Change another module's view with an AgentViewExtension instead.
 */
class AgentViewPathsTest {

    @Test
    void noAgentViewFileIsShippedByTwoModules() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:static/agent-views/**/*.*");
        Map<String, List<String>> byPath = new TreeMap<>();
        for (Resource r : files) {
            String url = r.getURL().toString();
            String path = url.substring(url.lastIndexOf("static/agent-views/"));
            byPath.computeIfAbsent(path, k -> new java.util.ArrayList<>()).add(url);
        }
        assertThat(byPath).isNotEmpty();
        assertThat(byPath).allSatisfy((path, sources) -> assertThat(sources).as(path).hasSize(1));
    }
}
