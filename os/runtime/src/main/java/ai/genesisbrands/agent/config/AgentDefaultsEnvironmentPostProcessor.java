package ai.genesisbrands.agent.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads every agent module's own defaults ({@code agents/<id>/agent.yml} on the classpath) as
 * the lowest-priority property source, under {@code genesis.agents.<id>.*}. An agent therefore
 * ships its own model/max-tokens/rag settings inside its jar, and an app's application.yml
 * only states what it changes — extension, not a copy of the agent's whole block.
 */
public class AgentDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "agentModuleDefaults";
    private static final String PATTERN = "classpath*:agents/*/agent.yml";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = new LinkedHashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(PATTERN)) {
                String agentId = agentIdOf(resource);
                YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(resource);
                Properties loaded = yaml.getObject();
                if (agentId == null || loaded == null) continue;
                loaded.forEach((k, v) -> props.put("genesis.agents." + agentId + "." + k, v));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load agent module defaults (" + PATTERN + ")", e);
        }
        if (!props.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, props));
        }
    }

    /** {@code .../agents/<id>/agent.yml} → {@code <id>}, for both file and jar URLs. */
    private static String agentIdOf(Resource resource) throws java.io.IOException {
        String url = resource.getURL().toString();
        int end = url.lastIndexOf("/agent.yml");
        int start = url.lastIndexOf('/', end - 1);
        return (end > 0 && start >= 0) ? url.substring(start + 1, end) : null;
    }
}
