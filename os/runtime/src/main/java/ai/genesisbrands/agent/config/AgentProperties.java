package ai.genesisbrands.agent.config;

import ai.genesisbrands.model.AgentSettingsOverride;
import ai.genesisbrands.usage.UsageContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent runtime config, in three layers (lowest first): the agent module's
 * {@code agents/<key>/agent.yml}, the app's {@code application.yml}, and admin overrides
 * ({@link AgentSettingsOverrideStore}). {@link #get} and {@link #systemPrompt} return the merged
 * result and are meant to be called per request, so admin changes apply without a restart.
 */
@Component
@ConfigurationProperties(prefix = "genesis")
public class AgentProperties {

    private Map<String, AgentConfig> agents = new java.util.HashMap<>();
    private final Map<String, String> classpathPrompts = new ConcurrentHashMap<>();
    private ObjectProvider<AgentSettingsOverrideStore> overrides;

    public Map<String, AgentConfig> getAgents() { return agents; }
    public void setAgents(Map<String, AgentConfig> agents) { this.agents = agents; }

    // Not a setter, so property binding never treats it as a "genesis.*" key.
    @Autowired
    void useOverrides(ObjectProvider<AgentSettingsOverrideStore> overrides) { this.overrides = overrides; }

    /** Effective config for a key: deploy-time defaults with any admin override applied. */
    public AgentConfig get(String agentId) {
        // Agents read their config right before each model call, which names the caller
        // for the usage ledger.
        UsageContext.noteAgent(agentId);
        AgentConfig base = defaults(agentId);
        Optional<AgentSettingsOverride> override = override(agentId);
        if (override.isEmpty()) return base;
        AgentSettingsOverride o = override.get();
        AgentConfig merged = base.copy();
        if (o.getModel() != null && !o.getModel().isBlank()) merged.setModel(o.getModel());
        if (o.getMaxTokens() != null) merged.setMaxTokens(o.getMaxTokens());
        if (o.getMaxIterations() != null) merged.setMaxIterations(o.getMaxIterations());
        if (o.getRagEnabled() != null) merged.getRag().setEnabled(o.getRagEnabled());
        if (o.getRagTopK() != null) merged.getRag().setTopK(o.getRagTopK());
        return merged;
    }

    /** Config from agent.yml + application.yml only, ignoring admin overrides. */
    public AgentConfig defaults(String agentId) {
        AgentConfig config = agents.get(agentId);
        if (config == null) throw new IllegalStateException("No agent config for: " + agentId);
        // Prompt by convention: an agent module ships agents/<id>/system.md, so its config
        // only names a prompt when it deliberately uses a different one.
        if (config.getSystemPrompt() == null) config.setSystemPrompt("agents/" + agentId + "/system.md");
        return config;
    }

    public boolean has(String agentId) { return agents.containsKey(agentId); }

    /** Effective system prompt text: the admin override if set, else the classpath prompt. */
    public String systemPrompt(String agentId) {
        return override(agentId)
            .map(AgentSettingsOverride::getSystemPrompt)
            .filter(p -> !p.isBlank())
            .orElseGet(() -> defaultSystemPrompt(agentId));
    }

    /** The classpath prompt the agent ships with (or the app points it at). */
    public String defaultSystemPrompt(String agentId) {
        String path = defaults(agentId).getSystemPrompt();
        return classpathPrompts.computeIfAbsent(path, p -> {
            try {
                return new ClassPathResource(p).getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load system prompt: " + p, e);
            }
        });
    }

    private Optional<AgentSettingsOverride> override(String agentId) {
        AgentSettingsOverrideStore store = overrides == null ? null : overrides.getIfAvailable();
        return store == null ? Optional.empty() : store.find(agentId);
    }

    public static class AgentConfig {
        private String model = "claude-sonnet-5";
        private String systemPrompt;
        private int maxTokens = 2000;
        private int maxIterations = 3;
        private RagConfig rag = new RagConfig();

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public RagConfig getRag() { return rag; }
        public void setRag(RagConfig rag) { this.rag = rag; }

        AgentConfig copy() {
            AgentConfig c = new AgentConfig();
            c.model = model;
            c.systemPrompt = systemPrompt;
            c.maxTokens = maxTokens;
            c.maxIterations = maxIterations;
            c.rag = new RagConfig();
            c.rag.enabled = rag.enabled;
            c.rag.topK = rag.topK;
            return c;
        }
    }

    public static class RagConfig {
        private boolean enabled = true;
        private int topK = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }
}
