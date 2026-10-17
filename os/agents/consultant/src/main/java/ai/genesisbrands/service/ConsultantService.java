package ai.genesisbrands.service;

import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.repository.ConversationMessageRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private static final String LEGACY_CONVERSATION_ID = "legacy";

    private static final String TOOL_USE_AGENT_ID = "consultant";

    private final ChatModel chatModel;
    private final RetrievalService retrieval;
    private final Layer1Service layer1;
    private final ConsultantSubjectProvider subjectProvider;
    private final ConversationMessageRepository messageRepo;
    private final TenantConsultantConfig tenantConfig;
    @Nullable
    private final ConsultantToolProvider toolProvider;
    private final AgentCatalogService catalogService;

    public ConsultantService(
        ChatModel chatModel,
        RetrievalService retrieval,
        Layer1Service layer1,
        ConsultantSubjectProvider subjectProvider,
        ConversationMessageRepository messageRepo,
        TenantConsultantConfig tenantConfig,
        @Nullable ConsultantToolProvider toolProvider,
        AgentCatalogService catalogService
    ) {
        this.chatModel = chatModel;
        this.retrieval = retrieval;
        this.layer1 = layer1;
        this.subjectProvider = subjectProvider;
        this.messageRepo = messageRepo;
        this.tenantConfig = tenantConfig;
        this.toolProvider = toolProvider;
        this.catalogService = catalogService;
    }

    public Flux<String> chat(String subjectId, String userMessage) {
        return chat(subjectId, null, userMessage, null, List.of(), ConversationMessage.Source.CONSULTANT);
    }

    public Flux<String> chat(String subjectId, String userMessage,
                             String attachmentText,
                             List<ContextEnrichmentService.UrlContent> urlContents,
                             ConversationMessage.Source source) {
        return chat(subjectId, null, userMessage, attachmentText, urlContents, source);
    }

    // conversationId groups CUSTOMER messages into separate, browsable threads (see
    // listConversations). Always null for CONSULTANT/PLAYGROUND, which stay single
    // continuous threads per subject — behavior for those sources is unchanged.
    public Flux<String> chat(String subjectId, String conversationId, String userMessage,
                             String attachmentText,
                             List<ContextEnrichmentService.UrlContent> urlContents,
                             ConversationMessage.Source source) {
        ConsultantSubject subject = subjectProvider.find(subjectId);

        save(subjectId, conversationId, "user", userMessage, source);

        if (tenantConfig.isOutOfScope(userMessage)) {
            String response = tenantConfig.outOfScopeResponse();
            save(subjectId, conversationId, "assistant", response, source);
            return Flux.just(response);
        }

        List<Message> messages = buildMessages(subject, conversationId, userMessage, attachmentText, urlContents, source);
        ChatOptions toolOptions = buildToolOptions(subjectId, source);
        Prompt prompt = toolOptions != null ? new Prompt(messages, toolOptions) : new Prompt(messages);

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
                    save(subjectId, conversationId, "assistant", fullResponse, source);
                }
            });
    }

    // Tools are only ever attached to a CUSTOMER-sourced chat — the admin/Playground "pure
    // consultant" sandbox never gets mutation capability, regardless of the toolsEnabled
    // toggle — and only when a tenant has both supplied a ConsultantToolProvider bean and
    // turned the toggle on via the agents admin page (AgentCatalogConfig.toolsEnabled).
    @Nullable
    private ChatOptions buildToolOptions(String subjectId, ConversationMessage.Source source) {
        if (toolProvider == null || source != ConversationMessage.Source.CUSTOMER) {
            return null;
        }
        if (!catalogService.isToolsEnabled(TOOL_USE_AGENT_ID)) {
            return null;
        }
        List<ToolCallback> tools = toolProvider.toolsFor(subjectId);
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return ToolCallingChatOptions.builder().toolCallbacks(tools).build();
    }

    public List<ConversationMessage> history(String subjectId, ConversationMessage.Source source) {
        return historyFor(subjectId, null, source);
    }

    // conversationId scoping is applied in-memory over the subject's full per-source
    // history (small per-subject volumes; avoids a new JPQL finder) — see historyFor.
    public List<ConversationMessage> history(String subjectId, String conversationId, ConversationMessage.Source source) {
        return historyFor(subjectId, conversationId, source);
    }

    // Groups a subject's CUSTOMER messages into separate conversations for the History tab.
    // Rows saved before conversationId existed (null) are grouped under a "legacy" sentinel
    // so they still surface as one browsable past conversation.
    public List<ConversationSummary> listConversations(String subjectId, ConversationMessage.Source source) {
        List<ConversationMessage> all = historyFor(subjectId, null, source);
        Map<String, List<ConversationMessage>> byConversation = all.stream()
            .collect(Collectors.groupingBy(
                m -> m.getConversationId() != null ? m.getConversationId() : LEGACY_CONVERSATION_ID,
                LinkedHashMap::new, Collectors.toList()));

        List<ConversationSummary> summaries = new ArrayList<>();
        for (var entry : byConversation.entrySet()) {
            List<ConversationMessage> msgs = entry.getValue();
            if (msgs.isEmpty()) continue;
            String title = msgs.stream()
                .filter(m -> "user".equals(m.getRole()))
                .findFirst()
                .map(ConversationMessage::getContent)
                .map(this::truncate)
                .orElse("Conversation");
            summaries.add(new ConversationSummary(entry.getKey(), title,
                msgs.get(0).getCreatedAt(), msgs.get(msgs.size() - 1).getCreatedAt()));
        }
        summaries.sort(Comparator.comparing(ConversationSummary::lastMessageAt).reversed());
        return summaries;
    }

    private String truncate(String text) {
        if (text == null) return "Conversation";
        String trimmed = text.strip();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60) + "…";
    }

    // PLAYGROUND only sees subjects that already have a playground-tagged message, so
    // testing never browses or exposes the real client-facing conversation list.
    // CONSULTANT (the real dashboard) excludes subjects that are playground-only — i.e.
    // ones that exist solely because they were created/messaged during Playground testing
    // and have never had a real consultant-side message — while still showing brand-new
    // real subjects that have no messages at all yet (so a first conversation can start).
    public List<ConsultantSubjectSummary> listSubjects(ConversationMessage.Source source) {
        List<ConsultantSubjectSummary> all = subjectProvider.list();
        if (source == ConversationMessage.Source.PLAYGROUND) {
            var playgroundSubjectIds = new java.util.HashSet<>(
                messageRepo.findDistinctSubjectIdsBySource(ConversationMessage.Source.PLAYGROUND));
            return all.stream().filter(s -> playgroundSubjectIds.contains(s.id())).toList();
        }
        var playgroundOnlyIds = new java.util.HashSet<>(
            messageRepo.findDistinctSubjectIdsBySource(ConversationMessage.Source.PLAYGROUND));
        playgroundOnlyIds.removeAll(messageRepo.findDistinctSubjectIdsWithConsultantActivity());
        return all.stream().filter(s -> !playgroundOnlyIds.contains(s.id())).toList();
    }

    public void clearHistory(String subjectId, ConversationMessage.Source source) {
        messageRepo.deleteAll(historyFor(subjectId, null, source));
    }

    // conversationId == null returns the subject's full per-source history (current
    // behavior for CONSULTANT/PLAYGROUND, and used internally by listConversations).
    // conversationId != null filters that history down to one conversation, treating
    // rows with a null conversationId field as the "legacy" bucket.
    private List<ConversationMessage> historyFor(String subjectId, String conversationId, ConversationMessage.Source source) {
        List<ConversationMessage> all = switch (source) {
            case PLAYGROUND -> messageRepo.findBySubjectIdAndSourceOrderByCreatedAtAsc(subjectId, ConversationMessage.Source.PLAYGROUND);
            case CUSTOMER -> messageRepo.findBySubjectIdAndSourceOrderByCreatedAtAsc(subjectId, ConversationMessage.Source.CUSTOMER);
            case CONSULTANT -> messageRepo.findConsultantHistoryBySubjectId(subjectId);
        };
        if (conversationId == null) {
            return all;
        }
        return all.stream()
            .filter(m -> conversationId.equals(m.getConversationId() != null ? m.getConversationId() : LEGACY_CONVERSATION_ID))
            .toList();
    }

    private List<Message> buildMessages(ConsultantSubject subject, String conversationId, String userMessage,
                                        String attachmentText,
                                        List<ContextEnrichmentService.UrlContent> urlContents,
                                        ConversationMessage.Source source) {
        List<Message> messages = new ArrayList<>();

        String systemContent = buildSystemContent(subject, userMessage, attachmentText, urlContents);
        messages.add(new SystemMessage(systemContent));

        List<ConversationMessage> history = historyFor(subject.id(), conversationId, source);
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

    private void save(String subjectId, String conversationId, String role, String content, ConversationMessage.Source source) {
        var msg = new ConversationMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setSubjectId(subjectId);
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSource(source);
        messageRepo.save(msg);
    }
}
