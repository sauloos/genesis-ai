package ai.genesisbrands.controller;

import ai.genesisbrands.platform.RegisteredByConfiguration;
import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.AgentCatalogService;
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
 * Customer-facing Consultant chat: the logged-in client's own consultant, grounded in the
 * subject the tenant's {@link ClientOwnSubjectResolver} derives from the session — never in
 * a subject id the client supplies. Deliberately takes no id in the URL or body.
 * A separate controller from {@link ConsultantController} (the admin/Playground "pure
 * consultant" facade) so that facade's client-suppliable {@code {id}} path param is never
 * reachable from this customer surface. Registered only when the tenant provides a
 * {@link ClientOwnSubjectResolver} (see {@code ConsultantServiceConfiguration}).
 */
@RestController
@RegisteredByConfiguration
@RequestMapping("/api/client/consultant")
@RequiredArgsConstructor
@Tag(name = "Client Consultant", description = "Customer-aware consultant chat, scoped to the logged-in client's own brand")
public class ClientConsultantController {

    private static final String CONSULTANT_AGENT_ID = "consultant";

    private final ConsultantService consultant;
    private final ClientAuthHelper clientAuthHelper;
    private final ClientOwnSubjectResolver subjectResolver;
    private final AgentCatalogService catalogService;

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

    /** Resolves the caller's own subject server-side via the tenant's resolver — never from a
     *  client-supplied id. 409 when the tenant has nothing to ground the consultant in yet. */
    private String resolveSubjectId(HttpServletRequest req) {
        catalogService.requireAvailableForLiveView(CONSULTANT_AGENT_ID);
        ClientUser client = clientAuthHelper.resolve(req)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));

        return subjectResolver.resolveOwnSubjectId(client)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No consultant subject is available for this account yet"));
    }

    public record ChatRequest(String conversationId, String message) {}
}
