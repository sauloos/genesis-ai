package ai.genesisbrands.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "genesis")
public class AgentProperties {

    private Map<String, AgentConfig> agents = new java.util.HashMap<>();

    public Map<String, AgentConfig> getAgents() { return agents; }
    public void setAgents(Map<String, AgentConfig> agents) { this.agents = agents; }

    public AgentConfig get(String agentId) {
        AgentConfig config = agents.get(agentId);
        if (config == null) throw new IllegalStateException("No agent config for: " + agentId);
        return config;
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
