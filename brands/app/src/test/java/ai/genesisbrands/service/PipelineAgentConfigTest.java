package ai.genesisbrands.service;

import ai.genesisbrands.agent.config.AgentDefaultsEnvironmentPostProcessor;
import ai.genesisbrands.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two derivation steps inside the engagement pipeline are config-only agents: no
 * {@code CoreAgent} bean, so nothing starts them and nothing fails if their config never binds.
 * That is precisely why it is pinned here — {@code BriefDerivationService} ran on a hardcoded
 * model and token cap until 2026-11-24, and the way that stayed invisible was that no test ever
 * asked where its numbers came from.
 *
 * <p>This exercises the real {@link AgentDefaultsEnvironmentPostProcessor} against the real
 * classpath, so it fails if an {@code agent.yml} is moved, renamed or dropped from the jar.
 */
class PipelineAgentConfigTest {

    private static AgentProperties props;

    @BeforeAll
    static void bindTheClasspathsAgentDefaults() {
        MockEnvironment environment = new MockEnvironment();
        new AgentDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        props = Binder.get(environment)
            .bind("genesis", AgentProperties.class)
            .orElseThrow(() -> new AssertionError("No genesis.agents.* bound from the classpath"));
    }

    @Test
    void theLegacyDerivationReadsItsModelAndCapFromConfig() {
        AgentProperties.AgentConfig config = props.defaults(BriefDerivationService.AGENT_ID);
        assertThat(config.getModel()).isEqualTo("claude-opus-5");
        assertThat(config.getMaxTokens()).isEqualTo(8000);
        // An engagement old enough to need this path predates the Layer 1 signal keys, so there is
        // nothing to tell Layer 2 precedent apart from noise for it.
        assertThat(config.getRag().isEnabled()).isFalse();
    }

    @Test
    void thePurposePlaybookDerivationReadsItsModelAndCapFromConfig() {
        AgentProperties.AgentConfig config = props.defaults(PurposePlaybookBriefDerivation.AGENT_ID);
        assertThat(config.getModel()).isEqualTo("claude-opus-5");
        assertThat(config.getMaxTokens()).isEqualTo(16000);
        assertThat(config.getRag().isEnabled()).isTrue();
    }

    @Test
    void bothDerivationsResolveARealSystemPromptByConvention() {
        for (String agentId : new String[]{BriefDerivationService.AGENT_ID,
                                           PurposePlaybookBriefDerivation.AGENT_ID}) {
            assertThat(props.defaults(agentId).getSystemPrompt())
                .as("prompt path for %s", agentId)
                .isEqualTo("agents/" + agentId + "/system.md");
            // Resolution is by convention with no registry, so the only thing standing between a
            // renamed directory and a runtime failure mid-engagement is that the file loads.
            assertThat(props.defaultSystemPrompt(agentId))
                .as("prompt text for %s", agentId)
                .contains("ANCHORED", "EVOLVED", "DISRUPTIVE");
        }
    }
}
