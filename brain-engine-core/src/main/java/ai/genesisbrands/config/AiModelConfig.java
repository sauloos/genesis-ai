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
     * Extended thinking is explicitly disabled here. Claude Sonnet 5 silently enables
     * implicit thinking by default (no opt-in needed) when the field is left unset —
     * confirmed via direct API calls against the real agent prompts in this codebase,
     * burning anywhere from ~400 to ~900+ output tokens on hidden reasoning before any
     * visible text. Since that cost is non-deterministic, an unlucky draw can exhaust
     * an agent's max_tokens budget entirely before any text starts, producing a
     * genuinely empty response that downstream JSON parsing then fails on ("No content
     * to map due to end-of-input") — this is what caused CopyAgent/PlaybookAgent/
     * BrandBookAgent to intermittently fail during live regeneration testing. An
     * earlier version of this comment claimed Anthropic's API rejects an explicit
     * "thinking.type: disabled" for this model family — re-verified directly against
     * the live API on 2026-10-02 and that is no longer true (or never was for
     * claude-sonnet-5): explicit disable is accepted, returns thinking_tokens: 0, and
     * every agent here generates single-shot structured JSON with no need for
     * chain-of-thought, so there's no reason to pay for or risk it.
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
