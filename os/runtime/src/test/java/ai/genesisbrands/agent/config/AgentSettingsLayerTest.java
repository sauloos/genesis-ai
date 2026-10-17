package ai.genesisbrands.agent.config;

import ai.genesisbrands.model.AgentSettingsOverride;
import ai.genesisbrands.platform.CoreAgent;
import ai.genesisbrands.service.AgentSettingsService;
import ai.genesisbrands.service.AgentSettingsService.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentSettingsLayerTest {

    private final Map<String, AgentSettingsOverride> rows = new HashMap<>();
    private AgentSettingsOverrideStore store;
    private AgentProperties props;

    @BeforeEach
    void setUp() {
        store = mock(AgentSettingsOverrideStore.class);
        when(store.find(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        when(store.save(any())).thenAnswer(i -> {
            AgentSettingsOverride o = i.getArgument(0);
            rows.put(o.getConfigKey(), o);
            return o;
        });
        doAnswer(i -> rows.remove(i.<String>getArgument(0))).when(store).delete(any());

        props = new AgentProperties();
        for (String key : List.of("sampleagent", "brand", "brand-book", "brand-book-evaluator")) {
            AgentProperties.AgentConfig c = new AgentProperties.AgentConfig();
            c.setSystemPrompt("agents/sampleagent/system.md");
            props.getAgents().put(key, c);
        }
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("store", store);
        ObjectProvider<AgentSettingsOverrideStore> provider = beans.getBeanProvider(AgentSettingsOverrideStore.class);
        props.useOverrides(provider);
    }

    @Test
    void noOverride_returnsDeployTimeConfig() {
        assertThat(props.get("sampleagent").getModel()).isEqualTo("claude-sonnet-5");
        assertThat(props.systemPrompt("sampleagent")).isEqualTo(props.defaultSystemPrompt("sampleagent"));
    }

    @Test
    void override_appliesOnlySetFields_andNeverMutatesDefaults() {
        AgentSettingsOverride o = new AgentSettingsOverride();
        o.setConfigKey("sampleagent");
        o.setModel("claude-opus-5");
        o.setRagTopK(9);
        o.setSystemPrompt("You are a test prompt.");
        rows.put("sampleagent", o);

        AgentProperties.AgentConfig merged = props.get("sampleagent");
        assertThat(merged.getModel()).isEqualTo("claude-opus-5");
        assertThat(merged.getRag().getTopK()).isEqualTo(9);
        assertThat(merged.getMaxTokens()).isEqualTo(2000);
        assertThat(props.systemPrompt("sampleagent")).isEqualTo("You are a test prompt.");

        assertThat(props.defaults("sampleagent").getModel()).isEqualTo("claude-sonnet-5");
        assertThat(props.defaults("sampleagent").getRag().getTopK()).isEqualTo(3);
    }

    @Test
    void blankPromptOverride_fallsBackToClasspathPrompt() {
        AgentSettingsOverride o = new AgentSettingsOverride();
        o.setConfigKey("sampleagent");
        o.setSystemPrompt("   ");
        rows.put("sampleagent", o);
        assertThat(props.systemPrompt("sampleagent")).isEqualTo(props.defaultSystemPrompt("sampleagent"));
    }

    @Test
    void settingsKeys_belongToTheLongestMatchingAgent() {
        AgentSettingsService service = new AgentSettingsService(List.of(agent("brand"), agent("brand-book")), props, store);
        assertThat(service.list("brand").stream().map(AgentSettingsService.SettingsGroup::key)).containsExactly("brand");
        assertThat(service.list("brand-book").stream().map(AgentSettingsService.SettingsGroup::key))
            .containsExactly("brand-book", "brand-book-evaluator");
        assertThatThrownBy(() -> service.update("brand", "brand-book-evaluator", empty()))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void update_validates_andAnAllEmptyOverrideIsRemoved() {
        AgentSettingsService service = new AgentSettingsService(List.of(agent("sampleagent")), props, store);
        assertThatThrownBy(() -> service.update("sampleagent", "sampleagent",
            new Settings(null, 0, null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("sampleagent", "sampleagent",
            new Settings("bad model id!", null, null, null, null, null))).isInstanceOf(IllegalArgumentException.class);

        service.update("sampleagent", "sampleagent", new Settings("claude-opus-5", null, null, null, null, ""));
        assertThat(rows.get("sampleagent").getModel()).isEqualTo("claude-opus-5");
        assertThat(rows.get("sampleagent").getSystemPrompt()).isNull();

        service.update("sampleagent", "sampleagent", empty());
        assertThat(rows).doesNotContainKey("sampleagent");
    }

    private static Settings empty() {
        return new Settings(null, null, null, null, null, null);
    }

    private static CoreAgent agent(String id) {
        CoreAgent a = mock(CoreAgent.class);
        when(a.agentId()).thenReturn(id);
        return a;
    }
}
