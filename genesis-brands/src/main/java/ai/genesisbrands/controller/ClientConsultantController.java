package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.ClientOwnSubjectResolver;
import ai.genesisbrands.service.ConsultantService;
import ai.genesisbrands.service.ConversationSummary;
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

/**
 * Customer-facing Consultant chat: the logged-in client's own consultant, grounded in their
 * own chosen-direction output (see {@code BrandConsultantSubjectProvider#findFromEngagement}),
 * never in a brand/subject id the client supplies. Deliberately takes no id in the URL or
 * body — the subject is always derived from the session, mirroring
 * {@link EngagementController}'s {@code resolveClientUserId}/{@code requireOwner} pattern.
 * A separate controller from {@link ConsultantController} (the admin/Playground "pure
 * consultant" facade) so that facade's client-suppliable {@code {id}} path param is never
 * reachable from this customer surface.
 */
@RestController
@RequestMapping("/api/client/consultant")
@RequiredArgsConstructor
@Tag(name = "Client Consultant", description = "Customer-aware consultant chat, scoped to the logged-in client's own brand")
public class ClientConsultantController {

    private final ConsultantService consultant;
    private final ClientAuthHelper clientAuthHelper;
    private final ClientOwnSubjectResolver subjectResolver;

    @PostMapping(
        value = "/chat",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @Operation(summary = "Send a message to your own consultant — returns a streaming SSE response, grounded in your brand.")
    public Flux<String> chat(@RequestBody ChatRequest req, HttpServletRequest servletReq) {
        String subjectId = resolveSubjectId(servletReq);
        return consultant.chat(subjectId, req.conversationId(), req.message(), null, List.of(), ConversationMessage.Source.CUSTOMER);
    }

    // conversationId is client-supplied (frontend generates it via crypto.randomUUID() for a
    // new chat) but this is safe: every read/write below is also scoped by subjectId, which is
    // always resolved server-side from the session — a guessed/collided conversationId under a
    // different subjectId simply matches zero rows, never another client's data.
    @GetMapping("/conversations")
    @Operation(summary = "List this client's own past consultant conversations")
    public List<ConversationSummary> conversations(HttpServletRequest req) {
        String subjectId = resolveSubjectId(req);
        return consultant.listConversations(subjectId, ConversationMessage.Source.CUSTOMER);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "Get the messages of one of this client's own past conversations")
    public List<ConversationMessage> conversationMessages(@PathVariable String conversationId, HttpServletRequest req) {
        String subjectId = resolveSubjectId(req);
        return consultant.history(subjectId, conversationId, ConversationMessage.Source.CUSTOMER);
    }

    /** Resolves the caller's own paid/finished engagement server-side — never from a
     *  client-supplied id — and returns the synthetic subject id BrandConsultantSubjectProvider
     *  resolves it from. Mirrors dashboardAgents/widget.js's resolveTarget() condition. */
    private String resolveSubjectId(HttpServletRequest req) {
        ClientUser client = clientAuthHelper.resolve(req)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));

        return subjectResolver.resolveOwnSubjectId(client)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Complete your brand journey first"));
    }

    public record ChatRequest(String conversationId, String message) {}
}
