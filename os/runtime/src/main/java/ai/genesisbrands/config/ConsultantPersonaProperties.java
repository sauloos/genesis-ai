package ai.genesisbrands.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "genesis.consultant")
public class ConsultantPersonaProperties {

    /** Classpath-relative path to the tenant's consultant system-prompt markdown. */
    private String systemPrompt = "agents/consultant/system-prompt.md";

    private List<String> outOfScopeKeywords = List.of();

    private String outOfScopeResponse = "That's outside what I can help with here.";

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public List<String> getOutOfScopeKeywords() { return outOfScopeKeywords; }
    public void setOutOfScopeKeywords(List<String> outOfScopeKeywords) { this.outOfScopeKeywords = outOfScopeKeywords; }
    public String getOutOfScopeResponse() { return outOfScopeResponse; }
    public void setOutOfScopeResponse(String outOfScopeResponse) { this.outOfScopeResponse = outOfScopeResponse; }
}
