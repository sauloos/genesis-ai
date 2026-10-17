package ai.genesisbrands.config;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.service.TenantConsultantConfig;

import java.util.List;

/**
 * Default tenant persona, driven entirely by configuration — onboarding a new tenant
 * means dropping a system-prompt markdown file into its resources and setting a few
 * properties under {@code genesis.agents.consultant.*} and {@code genesis.consultant.*},
 * not writing a Java class. The prompt is read per request, so an admin override applies
 * without a restart.
 * <p>
 * A tenant with more advanced needs (e.g. an LLM-based scope classifier) can still
 * supply its own {@link TenantConsultantConfig} bean, which takes priority — see
 * {@link ConsultantConfigConfiguration}.
 */
public class ConfigurableTenantConsultantConfig implements TenantConsultantConfig {

    static final String AGENT_ID = "consultant";

    private final ConsultantPersonaProperties properties;
    private final AgentProperties agentProperties;

    public ConfigurableTenantConsultantConfig(ConsultantPersonaProperties properties,
                                              AgentProperties agentProperties) {
        this.properties = properties;
        this.agentProperties = agentProperties;
    }

    @Override
    public String systemPrompt() {
        return agentProperties.systemPrompt(AGENT_ID);
    }

    @Override
    public List<String> outOfScopeKeywords() {
        return properties.getOutOfScopeKeywords();
    }

    @Override
    public String outOfScopeResponse() {
        return properties.getOutOfScopeResponse();
    }
}
