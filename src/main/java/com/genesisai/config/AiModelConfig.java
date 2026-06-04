package com.genesisai.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
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
