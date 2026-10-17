package ai.genesisbrands.controller;

import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.service.GenericAgentChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Admin/Playground facade for the generic chatBased core agents (Support/FAQ, Policy/
 * Compliance Q&A) — a single fixed "playground" thread per agent, with no live client
 * context. Simplified versus {@code ConsultantController}'s dual playground/consultant
 * origin split: these agents have no separate admin-dashboard surface like Consultant's
 * {@code console.html}, so there is only ever one admin-side thread to test against.
 */
@RestController
@RequestMapping("/api/agent-chat")
@RequiredArgsConstructor
@Tag(name = "Agent Chat", description = "Stateless-context Playground chat for chatBased generic core agents")
public class AgentChatController {

    private static final String PLAYGROUND_THREAD = "playground";

    private final GenericAgentChatService chatService;

    @PostMapping(value = "/{agentId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send a message to an agent's Playground test thread — streaming SSE response")
    public Flux<String> chat(@PathVariable String agentId, @RequestBody ChatRequest req) {
        return chatService.chat(agentId, PLAYGROUND_THREAD, req.message(), ConversationMessage.Source.PLAYGROUND, null);
    }

    @GetMapping("/{agentId}/history")
    @Operation(summary = "Get the Playground test thread's history for an agent")
    public List<ConversationMessage> history(@PathVariable String agentId) {
        return chatService.history(agentId, PLAYGROUND_THREAD, ConversationMessage.Source.PLAYGROUND);
    }

    @DeleteMapping("/{agentId}/history")
    @Operation(summary = "Clear the Playground test thread's history for an agent")
    public void clearHistory(@PathVariable String agentId) {
        chatService.clearHistory(agentId, PLAYGROUND_THREAD, ConversationMessage.Source.PLAYGROUND);
    }

    public record ChatRequest(String message) {}
}
