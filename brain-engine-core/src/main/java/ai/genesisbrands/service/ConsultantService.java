package ai.genesisbrands.service;

import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.repository.ConversationMessageRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Platform consultant facade — builds prompts, retrieves context, streams responses.
 * Persona (system prompt, scope guardrails) is owned by the tenant via {@link TenantConsultantConfig};
 * what a conversation is scoped to is owned by the tenant via {@link ConsultantSubjectProvider}.
 * Registered as a bean only when a tenant supplies a {@link ConsultantSubjectProvider}
 * (see {@code ConsultantServiceConfiguration}) — a bare platform deployment has none.
 */
public class ConsultantService {

    private static final int HISTORY_TURNS = 20;
    private static final int RETRIEVAL_TOP_K = 5;

    private final ChatModel chatModel;
    private final RetrievalService retrieval;
    private final Layer1Service layer1;
    private final ConsultantSubjectProvider subjectProvider;
    private final ConversationMessageRepository messageRepo;
    private final TenantConsultantConfig tenantConfig;

    public ConsultantService(
        ChatModel chatModel,
        RetrievalService retrieval,
        Layer1Service layer1,
        ConsultantSubjectProvider subjectProvider,
        ConversationMessageRepository messageRepo,
        TenantConsultantConfig tenantConfig
    ) {
        this.chatModel = chatModel;
        this.retrieval = retrieval;
        this.layer1 = layer1;
        this.subjectProvider = subjectProvider;
        this.messageRepo = messageRepo;
        this.tenantConfig = tenantConfig;
    }

    public Flux<String> chat(String subjectId, String userMessage) {
        return chat(subjectId, userMessage, null, List.of(), ConversationMessage.Source.CONSULTANT);
    }

    public Flux<String> chat(String subjectId, String userMessage,
                             String attachmentText,
                             List<ContextEnrichmentService.UrlContent> urlContents,
                             ConversationMessage.Source source) {
        ConsultantSubject subject = subjectProvider.find(subjectId);

        save(subjectId, "user", userMessage, source);

        if (tenantConfig.isOutOfScope(userMessage)) {
            String response = tenantConfig.outOfScopeResponse();
            save(subjectId, "assistant", response, source);
            return Flux.just(response);
        }

        List<Message> messages = buildMessages(subject, userMessage, attachmentText, urlContents, source);
        Prompt prompt = new Prompt(messages);

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

    public List<ConversationMessage> history(String subjectId, ConversationMessage.Source source) {
        return historyFor(subjectId, source);
    }

    public void clearHistory(String subjectId, ConversationMessage.Source source) {
        messageRepo.deleteAll(historyFor(subjectId, source));
    }

    private List<ConversationMessage> historyFor(String subjectId, ConversationMessage.Source source) {
        return source == ConversationMessage.Source.PLAYGROUND
            ? messageRepo.findBySubjectIdAndSourceOrderByCreatedAtAsc(subjectId, ConversationMessage.Source.PLAYGROUND)
            : messageRepo.findConsultantHistoryBySubjectId(subjectId);
    }

    private List<Message> buildMessages(ConsultantSubject subject, String userMessage,
                                        String attachmentText,
                                        List<ContextEnrichmentService.UrlContent> urlContents,
                                        ConversationMessage.Source source) {
        List<Message> messages = new ArrayList<>();

        String systemContent = buildSystemContent(subject, userMessage, attachmentText, urlContents);
        messages.add(new SystemMessage(systemContent));

        List<ConversationMessage> history = historyFor(subject.id(), source);
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

    private String buildSystemContent(ConsultantSubject subject, String query,
                                      String attachmentText,
                                      List<ContextEnrichmentService.UrlContent> urlContents) {
        var sb = new StringBuilder();

        sb.append(tenantConfig.systemPrompt()).append("\n\n");

        sb.append("# Current Context\n\n");
        sb.append("**Name:** ").append(subject.name()).append("\n");
        if (subject.industry() != null && !subject.industry().isBlank()) {
            sb.append("**Industry:** ").append(subject.industry()).append("\n");
        }
        if (subject.audience() != null && !subject.audience().isBlank()) {
            sb.append("**Audience:** ").append(subject.audience()).append("\n");
        }
        if (subject.brief() != null && !subject.brief().isBlank()) {
            sb.append("\n**Brief:**\n").append(subject.brief());
        }
        sb.append("\n\n");

        String layer1Context = layer1.buildContextBlock();
        if (!layer1Context.isBlank()) {
            sb.append(layer1Context).append("\n\n");
        }

        String retrieved = retrieval.retrieve(query, RETRIEVAL_TOP_K);
        if (!retrieved.isBlank()) {
            sb.append("# Relevant Knowledge\n\n");
            sb.append(retrieved).append("\n\n");
        }

        // Ephemeral context for this turn only (not persisted to conversation history)
        if (attachmentText != null && !attachmentText.isBlank()) {
            sb.append("# Attached Document\n\n");
            sb.append(attachmentText).append("\n\n");
        }

        if (urlContents != null && !urlContents.isEmpty()) {
            sb.append("# Referenced URLs\n\n");
            for (var uc : urlContents) {
                sb.append("**").append(uc.url()).append("**\n\n");
                sb.append(uc.text()).append("\n\n");
            }
        }

        return sb.toString();
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
