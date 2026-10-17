package ai.genesisbrands.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefaultsEnvironmentPostProcessorTest {

    private AgentProperties bind(Map<String, Object> appProps) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("app", appProps));
        new AgentDefaultsEnvironmentPostProcessor().postProcessEnvironment(env, null);
        return Binder.get(env).bind("genesis", AgentProperties.class).get();
    }

    @Test
    void agentYmlDefaults_bindUnderGenesisAgents_withPromptByConvention() {
        AgentProperties.AgentConfig c = bind(Map.of("genesis.other", "x")).get("sampleagent");
        assertThat(c.getModel()).isEqualTo("claude-haiku-4-5");
        assertThat(c.getMaxTokens()).isEqualTo(4000);
        assertThat(c.getRag().getTopK()).isEqualTo(7);
        assertThat(c.getSystemPrompt()).isEqualTo("agents/sampleagent/system.md");
    }

    @Test
    void appOverride_changesOnlyThatKey_andKeepsTheOtherDefaults() {
        AgentProperties.AgentConfig c = bind(Map.of("genesis.agents.sampleagent.model", "claude-sonnet-5")).get("sampleagent");
        assertThat(c.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(c.getMaxTokens()).isEqualTo(4000);
        assertThat(c.getRag().getTopK()).isEqualTo(7);
    }
}
