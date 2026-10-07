package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.ClientOwnSubjectResolver;
import ai.genesisbrands.service.GenericAgentChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

/**
 * Customer-facing surface for the generic chatBased core agents (Support/FAQ, Policy/
 * Compliance Q&A): one ongoing thread per logged-in client per agent, keyed by the
 * caller's own {@code clientUserId} resolved server-side — never a client-supplied id,
 * mirroring {@code ClientConsultantController}'s security boundary. Live brand-context
 * enrichment degrades gracefully to none when no {@link ClientOwnSubjectResolver} is
 * registered or the client has no resolvable "own subject" yet.
 */
@RestController
@RequestMapping("/api/client/agent-chat")
@RequiredArgsConstructor
@Tag(name = "Client Agent Chat", description = "Customer-aware chat for chatBased generic core agents")
public class ClientAgentChatController {

    private final GenericAgentChatService chatService;
    private final ClientAuthHelper clientAuthHelper;
    private final Optional<ClientOwnSubjectResolver> subjectResolver;

    @PostMapping(value = "/{agentId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send a message to your own thread with this agent — streaming SSE response")
    public Flux<String> chat(@PathVariable String agentId, @RequestBody ChatRequest req, HttpServletRequest servletReq) {
        ClientUser client = requireClient(servletReq);
        String liveSubjectId = subjectResolver
            .flatMap(resolver -> resolver.resolveOwnSubjectId(client))
            .orElse(null);
        return chatService.chat(agentId, client.getId(), req.message(), ConversationMessage.Source.CUSTOMER, liveSubjectId);
    }

    @GetMapping("/{agentId}/history")
    @Operation(summary = "Get your own thread's history with this agent")
    public List<ConversationMessage> history(@PathVariable String agentId, HttpServletRequest req) {
        ClientUser client = requireClient(req);
        return chatService.history(agentId, client.getId(), ConversationMessage.Source.CUSTOMER);
    }

    private ClientUser requireClient(HttpServletRequest req) {
        return clientAuthHelper.resolve(req)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));
    }

    public record ChatRequest(String message) {}
}
