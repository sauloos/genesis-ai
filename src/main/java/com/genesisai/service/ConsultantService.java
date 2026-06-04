package com.genesisai.service;

import com.genesisai.model.Brand;
import com.genesisai.model.ConversationMessage;
import com.genesisai.repository.BrandRepository;
import com.genesisai.repository.ConversationMessageRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core Genesis AI consultant — builds prompts, retrieves context, streams responses.
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

    @Value("${genesis.agent.system-prompt-path:agents/genesis-ai/system-prompt.md}")
    private String systemPromptPath;

    public ConsultantService(
        ChatModel chatModel,
        RetrievalService retrieval,
        Layer1Service layer1,
        BrandRepository brandRepo,
        ConversationMessageRepository messageRepo
    ) {
        this.chatModel = chatModel;
        this.retrieval = retrieval;
        this.layer1 = layer1;
        this.brandRepo = brandRepo;
        this.messageRepo = messageRepo;
    }

    private static final String OUT_OF_SCOPE_RESPONSE =
        "That's outside my territory. I'm a brand strategy creative director — " +
        "if you have a question about your brand, positioning, identity, or marketing, I'm here for that.";

    private static final List<String> OUT_OF_SCOPE_PATTERNS = List.of(
        "recipe", "ingredient", "bake", "cook", "preheat", "tablespoon", "teaspoon", "flour", "butter",
        "trim()", ".trim", "java method", "python ", "javascript ", "c++ ", "sql query", "html tag",
        "css style", "git commit", "docker ", "kubernetes", "regex ", "algorithm ", "data structure",
        "neural network", "machine learning model", "what is rag", "what is llm", "what is gpt",
        "what is bert", "transformer model", "flight to", "hotel in", "visa for",
        "medical ", "diagnosis ", "symptom ", "medication ", "doctor "
    );

    private boolean isOutOfScope(String message) {
        String lower = message.toLowerCase();
        return OUT_OF_SCOPE_PATTERNS.stream().anyMatch(lower::contains);
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

        if (isOutOfScope(userMessage)) {
            save(brandId, "assistant", OUT_OF_SCOPE_RESPONSE);
            return Flux.just(OUT_OF_SCOPE_RESPONSE);
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

        sb.append(loadSystemPrompt()).append("\n\n");

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

    private String loadSystemPrompt() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(systemPromptPath));
        } catch (IOException e) {
            return "You are Genesis AI, a brand strategy creative director.";
        }
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
