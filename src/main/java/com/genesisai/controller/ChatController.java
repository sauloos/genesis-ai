package com.genesisai.controller;

import com.genesisai.model.ConversationMessage;
import com.genesisai.repository.ConversationMessageRepository;
import com.genesisai.service.ConsultantService;
import com.genesisai.service.ContextEnrichmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/brands/{brandId}/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Conversational interface with Genesis AI creative director")
public class ChatController {

    private final ConsultantService consultant;
    private final ContextEnrichmentService enrichment;
    private final ConversationMessageRepository messageRepo;

    @PostMapping(
        consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE},
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @Operation(summary = "Send a message to Genesis AI — returns a streaming SSE response. " +
                         "Optionally attach a PDF or text file for additional context. " +
                         "URLs in the message are fetched automatically.")
    public Flux<String> chat(
        @PathVariable String brandId,
        @RequestPart("message") String message,
        @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        String attachmentText = enrichment.extractFromFile(file);
        List<ContextEnrichmentService.UrlContent> urlContents = enrichment.fetchUrls(message);
        return consultant.chat(brandId, message, attachmentText, urlContents);
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
}
