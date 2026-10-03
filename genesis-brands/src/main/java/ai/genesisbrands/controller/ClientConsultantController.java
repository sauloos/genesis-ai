package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.ConsultantService;
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

    private static final String ENGAGEMENT_PREFIX = "engagement:";

    private final ConsultantService consultant;
    private final ClientAuthHelper clientAuthHelper;
    private final EngagementRepository engagementRepo;

    @PostMapping(
        value = "/chat",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @Operation(summary = "Send a message to your own consultant — returns a streaming SSE response, grounded in your brand.")
    public Flux<String> chat(@RequestBody ChatRequest req, HttpServletRequest servletReq) {
        String subjectId = resolveSubjectId(servletReq);
        return consultant.chat(subjectId, req.message(), null, List.of(), ConversationMessage.Source.CUSTOMER);
    }

    @GetMapping("/chat/history")
    @Operation(summary = "Get this client's own consultant conversation history")
    public List<ConversationMessage> history(HttpServletRequest req) {
        String subjectId = resolveSubjectId(req);
        return consultant.history(subjectId, ConversationMessage.Source.CUSTOMER);
    }

    /** Resolves the caller's own paid/finished engagement server-side — never from a
     *  client-supplied id — and returns the synthetic subject id BrandConsultantSubjectProvider
     *  resolves it from. Mirrors dashboardAgents/widget.js's resolveTarget() condition. */
    private String resolveSubjectId(HttpServletRequest req) {
        ClientUser client = clientAuthHelper.resolve(req)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));

        Engagement target = engagementRepo.findAllByClientUserIdOrderByCreatedAtDesc(client.getId()).stream()
            .filter(e -> e.getStatus() == Engagement.Status.DONE
                && e.getPaymentStatus() == Engagement.PaymentStatus.PAID
                && e.getChosenDirection() != null)
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Complete your brand journey first"));

        return ENGAGEMENT_PREFIX + target.getId();
    }

    public record ChatRequest(String message) {}
}
