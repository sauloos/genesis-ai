package ai.genesisbrands.service;

import ai.genesisbrands.agent.config.AgentProperties;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared single-shot ("CREATE") execution path for every generic core agent that isn't
 * a stateful chat (e.g. Summarizer, RFP Response, Research & Synthesis, Writer). Mirrors
 * {@code CopyAgent}'s model/prompt pattern (one shared {@link ChatClient} bean, per-call
 * {@link AnthropicChatOptions}) but driven entirely by {@link AgentProperties} instead of
 * one bespoke class per agent.
 */
@Service
public class GenericSingleShotAgentService {

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final AgentRagContextService ragContext;
    private final Map<String, String> systemPromptCache = new ConcurrentHashMap<>();

    public GenericSingleShotAgentService(ChatClient.Builder chatClientBuilder,
                                          AgentProperties agentProperties,
                                          AgentRagContextService ragContext) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.ragContext = ragContext;
    }

    /**
     * @param liveSubjectId null on Playground (no owning client); the caller's own
     *                      resolved subject id on a customer-facing Live request.
     */
    public String generate(String agentId, String input, String liveSubjectId) {
        AgentProperties.AgentConfig config = agentProperties.get(agentId);
        String systemPrompt = loadSystemPrompt(agentId, config);

        String context = agentProperties.get(agentId).getRag().isEnabled()
            ? ragContext.buildContext(input, liveSubjectId)
            : "";

        String userPrompt = context.isBlank()
            ? input
            : context + "\n\n# Request\n\n" + input;

        return chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .options(AnthropicChatOptions.builder()
                .model(config.getModel())
                .maxTokens(config.getMaxTokens())
                .build())
            .call()
            .content();
    }

    private String loadSystemPrompt(String agentId, AgentProperties.AgentConfig config) {
        return systemPromptCache.computeIfAbsent(agentId, id -> {
            try {
                return new ClassPathResource(config.getSystemPrompt())
                    .getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load system prompt: " + config.getSystemPrompt(), e);
            }
        });
    }
}
