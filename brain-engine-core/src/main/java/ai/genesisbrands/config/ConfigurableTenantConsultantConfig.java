package ai.genesisbrands.config;

import ai.genesisbrands.service.TenantConsultantConfig;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Default tenant persona, driven entirely by configuration — onboarding a new tenant
 * means dropping a system-prompt markdown file into its resources and setting a few
 * properties under {@code genesis.consultant.*}, not writing a Java class.
 * <p>
 * A tenant with more advanced needs (e.g. an LLM-based scope classifier) can still
 * supply its own {@link TenantConsultantConfig} bean, which takes priority — see
 * {@link ConsultantConfigConfiguration}.
 */
public class ConfigurableTenantConsultantConfig implements TenantConsultantConfig {

    private final ConsultantPersonaProperties properties;
    private final String systemPrompt;

    public ConfigurableTenantConsultantConfig(ConsultantPersonaProperties properties) {
        this.properties = properties;
        this.systemPrompt = loadSystemPrompt(properties.getSystemPrompt());
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public List<String> outOfScopeKeywords() {
        return properties.getOutOfScopeKeywords();
    }

    @Override
    public String outOfScopeResponse() {
        return properties.getOutOfScopeResponse();
    }

    private String loadSystemPrompt(String classpathPath) {
        try {
            return new ClassPathResource(classpathPath).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load consultant system prompt from " + classpathPath, e);
        }
    }
}
