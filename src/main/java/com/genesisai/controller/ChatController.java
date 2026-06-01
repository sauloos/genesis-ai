package com.genesisai.controller;

import com.genesisai.model.ConversationMessage;
import com.genesisai.repository.ConversationMessageRepository;
import com.genesisai.service.ConsultantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/brands/{brandId}/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Conversational interface with Genesis AI creative director")
public class ChatController {

    private final ConsultantService consultant;
    private final ConversationMessageRepository messageRepo;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send a message to Genesis AI — returns a streaming SSE response")
    public Flux<String> chat(
        @PathVariable String brandId,
        @RequestBody ChatRequest req
    ) {
        return consultant.chat(brandId, req.message());
    }

    @GetMapping("/history")
    @Operation(summary = "Get conversation history for a brand")
    public List<ConversationMessage> history(@PathVariable String brandId) {
        return messageRepo.findByBrandIdOrderByCreatedAtAsc(brandId);
    }

    @DeleteMapping("/history")
    @Operation(summary = "Clear conversation history for a brand")
    public void clearHistory(@PathVariable String brandId) {
        var messages = messageRepo.findByBrandIdOrderByCreatedAtAsc(brandId);
        messageRepo.deleteAll(messages);
    }

    public record ChatRequest(String message) {}
}
