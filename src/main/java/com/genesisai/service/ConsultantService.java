package com.genesisai.service;

import com.genesisai.model.Brand;
import com.genesisai.model.ConversationMessage;
import com.genesisai.repository.BrandRepository;
import com.genesisai.repository.ConversationMessageRepository;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private final AnthropicChatModel chatModel;
    private final RetrievalService retrieval;
    private final Layer1Service layer1;
    private final BrandRepository brandRepo;
    private final ConversationMessageRepository messageRepo;

    @Value("${genesis.agent.system-prompt-path:agents/genesis-ai/system-prompt.md}")
    private String systemPromptPath;

    public ConsultantService(
        AnthropicChatModel chatModel,
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

    public Flux<String> chat(String brandId, String userMessage) {
        Brand brand = brandRepo.findById(brandId)
            .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + brandId));

        // Persist user message
        save(brandId, "user", userMessage);

        // Build prompt
        List<Message> messages = buildMessages(brand, userMessage);
        Prompt prompt = new Prompt(messages);

        // Stream response, accumulate for persistence
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

    private List<Message> buildMessages(Brand brand, String userMessage) {
        List<Message> messages = new ArrayList<>();

        // System message: persona + layer 1 + brand context + retrieved knowledge
        String systemContent = buildSystemContent(brand, userMessage);
        messages.add(new SystemMessage(systemContent));

        // Conversation history (last N turns)
        List<ConversationMessage> history = messageRepo.findByBrandIdOrderByCreatedAtAsc(brand.getId());
        int start = Math.max(0, history.size() - (HISTORY_TURNS * 2));
        for (int i = start; i < history.size(); i++) {
            ConversationMessage m = history.get(i);
            messages.add("user".equals(m.getRole())
                ? new UserMessage(m.getContent())
                : new AssistantMessage(m.getContent()));
        }

        // Current user turn
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    private String buildSystemContent(Brand brand, String query) {
        var sb = new StringBuilder();

        // Persona (system-prompt.md)
        sb.append(loadSystemPrompt()).append("\n\n");

        // Brand context
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

        // Layer 1 methodology
        String layer1Context = layer1.buildContextBlock();
        if (!layer1Context.isBlank()) {
            sb.append(layer1Context).append("\n\n");
        }

        // Retrieved knowledge relevant to this query
        String retrieved = retrieval.retrieve(query, RETRIEVAL_TOP_K);
        if (!retrieved.isBlank()) {
            sb.append("# Relevant Knowledge\n\n");
            sb.append("The following excerpts from the agency's knowledge base are relevant to this conversation.\n\n");
            sb.append(retrieved).append("\n\n");
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
