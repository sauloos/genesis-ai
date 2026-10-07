package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.GeneratedDocument;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.ClientOwnSubjectResolver;
import ai.genesisbrands.service.GeneratedDocumentService;
import ai.genesisbrands.service.GenericSingleShotAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Customer-facing surface for the generic CREATE-shaped core agents (Summarizer, RFP
 * Response, Research & Synthesis, Writer): generates and persists one
 * {@link GeneratedDocument} per call, always scoped to the logged-in client resolved
 * server-side — never a client-supplied id, mirroring {@code ClientConsultantController}'s
 * security boundary. Live client brand context is enriched only when a tenant has
 * registered a {@link ClientOwnSubjectResolver} AND that client has a resolvable "own
 * subject" (e.g. a finished engagement); otherwise the agent still runs, just without
 * that enrichment — this controller never fails a request for missing live context.
 */
@RestController
@RequestMapping("/api/client/generated-documents")
@RequiredArgsConstructor
@Tag(name = "Generated Documents", description = "Customer-facing generation + history for CREATE-shaped generic core agents")
public class GeneratedDocumentController {

    private final GenericSingleShotAgentService generationService;
    private final GeneratedDocumentService documentService;
    private final ClientAuthHelper clientAuthHelper;
    private final Optional<ClientOwnSubjectResolver> subjectResolver;

    @GetMapping
    @Operation(summary = "List this client's own past generations for one agent")
    public List<GeneratedDocument> list(@RequestParam String agentId, HttpServletRequest req) {
        ClientUser client = requireClient(req);
        return documentService.listFor(agentId, client.getId());
    }

    @PostMapping
    @Operation(summary = "Generate and persist one document, grounded in this client's own brand context where available")
    public GeneratedDocument generate(@RequestBody GenerateRequest req, HttpServletRequest servletReq) {
        ClientUser client = requireClient(servletReq);

        String liveSubjectId = subjectResolver
            .flatMap(resolver -> resolver.resolveOwnSubjectId(client))
            .orElse(null);

        String output = generationService.generate(req.agentId(), req.input(), liveSubjectId);
        return documentService.save(req.agentId(), client.getId(), req.title(), output);
    }

    private ClientUser requireClient(HttpServletRequest req) {
        return clientAuthHelper.resolve(req)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));
    }

    public record GenerateRequest(String agentId, String input, String title) {}
}
