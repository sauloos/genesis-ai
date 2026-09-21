package ai.genesisbrands.service;

import ai.genesisbrands.model.Brand;
import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.repository.BrandRepository;
import ai.genesisbrands.repository.ConversationMessageRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Platform consultant facade — builds prompts, retrieves context, streams responses.
 * Persona (system prompt, scope guardrails) is owned by the tenant via {@link TenantConsultantConfig}.
 */
@Service
public class ConsultantService {

    private static final int HISTORY_TURNS = 20;
    private static final int RETRIEVAL_TOP_K = 5;

    private final ChatModel chatModel;
    private final RetrievalService retrieval;
    private final Layer1Service layer1;
    private final BrandRepository brandRepo;
    private final ConversationMessageRepository messageRepo;
    private final TenantConsultantConfig tenantConfig;

    public ConsultantService(
        ChatModel chatModel,
        RetrievalService retrieval,
        Layer1Service layer1,
        BrandRepository brandRepo,
        ConversationMessageRepository messageRepo,
        TenantConsultantConfig tenantConfig
    ) {
        this.chatModel = chatModel;
        this.retrieval = retrieval;
        this.layer1 = layer1;
        this.brandRepo = brandRepo;
        this.messageRepo = messageRepo;
        this.tenantConfig = tenantConfig;
    }

    public Flux<String> chat(String brandId, String userMessage) {
        return chat(brandId, userMessage, null, List.of());
    }

    public Flux<String> chat(String brandId, String userMessage,
                             String attachmentText,
                             List<ContextEnrichmentService.UrlContent> urlContents) {
        Brand brand = brandRepo.findById(brandId)
            .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + brandId));

        save(brandId, "user", userMessage);

        if (tenantConfig.isOutOfScope(userMessage)) {
            String response = tenantConfig.outOfScopeResponse();
            save(brandId, "assistant", response);
            return Flux.just(response);
        }

        List<Message> messages = buildMessages(brand, userMessage, attachmentText, urlContents);
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
                    save(brandId, "assistant", fullResponse);
                }
            });
    }

    private List<Message> buildMessages(Brand brand, String userMessage,
                                        String attachmentText,
                                        List<ContextEnrichmentService.UrlContent> urlContents) {
        List<Message> messages = new ArrayList<>();

        String systemContent = buildSystemContent(brand, userMessage, attachmentText, urlContents);
        messages.add(new SystemMessage(systemContent));

        List<ConversationMessage> history = messageRepo.findByBrandIdOrderByCreatedAtAsc(brand.getId());
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

    private String buildSystemContent(Brand brand, String query,
                                      String attachmentText,
                                      List<ContextEnrichmentService.UrlContent> urlContents) {
        var sb = new StringBuilder();

        sb.append(tenantConfig.systemPrompt()).append("\n\n");

        sb.append("# Current Brand Context\n\n");
        sb.append("**Brand:** ").append(brand.getName()).append("\n");
        if (brand.getIndustry() != null && !brand.getIndustry().isBlank()) {
            sb.append("**Industry:** ").append(brand.getIndustry()).append("\n");
        }
        if (brand.getAudience() != null && !brand.getAudience().isBlank()) {
            sb.append("**Audience:** ").append(brand.getAudience()).append("\n");
        }
        if (brand.getBrief() != null && !brand.getBrief().isBlank()) {
            sb.append("\n**Brief:**\n").append(brand.getBrief());
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

    private void save(String brandId, String role, String content) {
        var msg = new ConversationMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setBrandId(brandId);
        msg.setRole(role);
        msg.setContent(content);
        messageRepo.save(msg);
    }
}
