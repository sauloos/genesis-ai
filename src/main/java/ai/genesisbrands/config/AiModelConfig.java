package ai.genesisbrands.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Designates the primary ChatModel and EmbeddingModel beans per active profile.
 *
 * Default profile: Anthropic (Claude) for chat + OpenAI for embeddings — direct API keys.
 *   Switch to this to use your own Anthropic/OpenAI keys (Option 2).
 *
 * Azure profile: GPT-4o for chat + text-embedding-3-small for embeddings — all via Azure
 *   OpenAI in uksouth, burns Azure trial credits (Option 1).
 *   When Claude becomes available on Azure AI Foundry, update application-azure.yml
 *   to add AZURE_AI_FOUNDRY_* vars and change the chat bean to use OpenAiChatModel.
 *
 * Toggle: set SPRING_PROFILES_ACTIVE=azure on the Container App (or locally in env).
 */
@Configuration
public class AiModelConfig {

    /**
     * Overrides the auto-configured AnthropicChatModel bean to build default options
     * without a temperature value. Spring AI's AnthropicChatProperties hardcodes
     * temperature=0.8 as a default, and its request-merge logic ignores null fields
     * from per-call options — so any agent that leaves temperature unset silently
     * inherits 0.8 with no way to override it away at the call site. Newer Claude
     * models (e.g. claude-sonnet-5) reject the temperature parameter outright
     * ("temperature is deprecated for this model"), so it must never be sent unless
     * a caller explicitly opts in.
     *
     * Also disables extended thinking by default. claude-sonnet-5 returns a
     * "thinking" content block ahead of the "text" block even when thinking was
     * never requested. Spring AI 1.1.7 turns each content block into its own
     * Generation, so ChatClient.call().content() — which reads only the first
     * Generation — silently returns the (empty) thinking text instead of the
     * real JSON output. Same null-never-overrides-bean-default merge behavior as
     * temperature, so this has to be set here rather than per-agent.
     */
    @Bean
    public AnthropicChatModel anthropicChatModel(AnthropicApi anthropicApi, AnthropicChatProperties chatProperties) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
            .model(chatProperties.getOptions().getModel())
            .maxTokens(chatProperties.getOptions().getMaxTokens())
            .thinking(AnthropicApi.ThinkingType.DISABLED, null)
            .build();
        return AnthropicChatModel.builder()
            .anthropicApi(anthropicApi)
            .defaultOptions(options)
            .build();
    }

    // --- Default profile (direct API keys) ---

    @Bean
    @Primary
    @Profile("!azure")
    public ChatModel primaryChatModel(AnthropicChatModel model) {
        return model;
    }

    @Bean
    @Primary
    @Profile("!azure")
    public EmbeddingModel primaryEmbeddingModel(OpenAiEmbeddingModel model) {
        return model;
    }

    // --- Azure profile (Azure OpenAI — burns Azure credits) ---

    @Bean
    @Primary
    @Profile("azure")
    public ChatModel azureChatModel(AzureOpenAiChatModel model) {
        return model;
    }

    @Bean
    @Primary
    @Profile("azure")
    public EmbeddingModel azureEmbeddingModel(AzureOpenAiEmbeddingModel model) {
        return model;
    }
}
