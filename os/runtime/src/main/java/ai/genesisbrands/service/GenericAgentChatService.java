package ai.genesisbrands.service;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.repository.ConversationMessageRepository;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared multi-turn chat execution path for the generic chatBased core agents (e.g.
 * Support/FAQ, Policy/Compliance Q&A) — the chat-based sibling of
 * {@link GenericSingleShotAgentService}, mirroring {@link ConsultantService}'s
 * prompt/history/streaming shape without duplicating Consultant's own tool-use/persona
 * machinery, which these simpler agents don't need.
 * <p>
 * Reuses {@link ConversationMessage} as-is via a synthetic subject id
 * ({@code "agent:" + agentId + ":" + threadKey}) scoped by the existing
 * {@link ConversationMessage.Source} — PLAYGROUND for the admin/Playground thread,
 * CUSTOMER for a logged-in client's own thread — so no schema change or new repository
 * query is needed.
 * <p>
 * Deliberately builds the message history <em>before</em> persisting the new user turn
 * (unlike {@code ConsultantService}, which saves first) to avoid appending the
 * just-saved message twice into the same prompt.
 */
@Service
public class GenericAgentChatService {

    private static final int HISTORY_TURNS = 20;

    private final ChatModel chatModel;
    private final AgentProperties agentProperties;
    private final AgentRagContextService ragContext;
    private final ConversationMessageRepository messageRepo;
    private final Map<String, String> systemPromptCache = new ConcurrentHashMap<>();

    public GenericAgentChatService(ChatModel chatModel,
                                    AgentProperties agentProperties,
                                    AgentRagContextService ragContext,
                                    ConversationMessageRepository messageRepo) {
        this.chatModel = chatModel;
        this.agentProperties = agentProperties;
        this.ragContext = ragContext;
        this.messageRepo = messageRepo;
    }

    /**
     * @param liveSubjectId null on Playground (admin thread, no owning client); the
     *                      caller's own resolved subject id on a customer-facing chat.
     */
    public Flux<String> chat(String agentId, String threadKey, String userMessage,
                              ConversationMessage.Source source, String liveSubjectId) {
        String subjectId = subjectId(agentId, threadKey);

        List<Message> messages = buildMessages(agentId, subjectId, source, userMessage, liveSubjectId);
        save(subjectId, "user", userMessage, source);

        AgentProperties.AgentConfig config = agentProperties.get(agentId);
        Prompt prompt = new Prompt(messages, AnthropicChatOptions.builder()
            .model(config.getModel())
            .maxTokens(config.getMaxTokens())
            .build());

        StringBuilder responseBuffer = new StringBuilder();

        return chatModel.stream(prompt)
            .mapNotNull(response -> {
                if (response.getResult() == null) return null;
                String token = response.getResult().getOutput().getText();
                if (token != null && !token.isEmpty()) {
                    responseBuffer.append(token);
                }
                return token;
            })
            .filter(token -> token != null && !token.isEmpty())
            .doOnComplete(() -> {
                String fullResponse = responseBuffer.toString();
                if (!fullResponse.isBlank()) {
                    save(subjectId, "assistant", fullResponse, source);
                }
            });
    }

    public List<ConversationMessage> history(String agentId, String threadKey, ConversationMessage.Source source) {
        return messageRepo.findBySubjectIdAndSourceOrderByCreatedAtAsc(subjectId(agentId, threadKey), source);
    }

    public void clearHistory(String agentId, String threadKey, ConversationMessage.Source source) {
        messageRepo.deleteAll(history(agentId, threadKey, source));
    }

    private String subjectId(String agentId, String threadKey) {
        return "agent:" + agentId + ":" + threadKey;
    }

    private List<Message> buildMessages(String agentId, String subjectId, ConversationMessage.Source source,
                                         String userMessage, String liveSubjectId) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemContent(agentId, userMessage, liveSubjectId)));

        List<ConversationMessage> history = messageRepo.findBySubjectIdAndSourceOrderByCreatedAtAsc(subjectId, source);
        int start = Math.max(0, history.size() - (HISTORY_TURNS * 2));
        for (int i = start; i < history.size(); i++) {
            ConversationMessage m = history.get(i);
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            messages.add("user".equals(m.getRole())
                ? new UserMessage(m.getContent())
                : new AssistantMessage(m.getContent()));
        }

        messages.add(new UserMessage(userMessage));
        return messages;
    }

    private String buildSystemContent(String agentId, String query, String liveSubjectId) {
        String systemPrompt = loadSystemPrompt(agentId);
        String context = ragContext.buildContext(query, liveSubjectId);
        return context.isBlank() ? systemPrompt : systemPrompt + "\n\n" + context;
    }

    private String loadSystemPrompt(String agentId) {
        return systemPromptCache.computeIfAbsent(agentId, id -> {
            AgentProperties.AgentConfig config = agentProperties.get(id);
            try {
                return new ClassPathResource(config.getSystemPrompt())
                    .getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load system prompt: " + config.getSystemPrompt(), e);
            }
        });
    }

    private void save(String subjectId, String role, String content, ConversationMessage.Source source) {
        var msg = new ConversationMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setSubjectId(subjectId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSource(source);
        messageRepo.save(msg);
    }
}
