package ai.genesisbrands.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "genesis.consultant")
public class ConsultantPersonaProperties {

    // The persona prompt, model and RAG settings live under genesis.agents.consultant.*
    // (agent.yml + app overrides + admin settings), like every other agent.

    private List<String> outOfScopeKeywords = List.of();

    private String outOfScopeResponse = "That's outside what I can help with here.";

    public List<String> getOutOfScopeKeywords() { return outOfScopeKeywords; }
    public void setOutOfScopeKeywords(List<String> outOfScopeKeywords) { this.outOfScopeKeywords = outOfScopeKeywords; }
    public String getOutOfScopeResponse() { return outOfScopeResponse; }
    public void setOutOfScopeResponse(String outOfScopeResponse) { this.outOfScopeResponse = outOfScopeResponse; }
}
