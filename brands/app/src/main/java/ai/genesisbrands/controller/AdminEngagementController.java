package ai.genesisbrands.controller;

import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.EngagementOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Admin-only engagement operations. Lives under {@code /api/admin/} rather than
 * {@code /api/engagements} because the latter is reachable with the shared public API key,
 * and a rerun spends real model money.
 */
@RestController
@RequestMapping("/api/admin/engagements")
@RequiredArgsConstructor
@Tag(name = "Engagements (admin)")
public class AdminEngagementController {

    private static final Logger log = LoggerFactory.getLogger(AdminEngagementController.class);

    private final EngagementRepository engagementRepo;
    private final EngagementOrchestratorService orchestrator;
    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    public record RerunRequest(String questionnaireResponseId) {}

    /**
     * Runs the whole pipeline again for an existing engagement, in place: same id, same owner,
     * same flow-session pointer, so the client's results page and dashboard pick up the new
     * directions without anything else changing. Everything the last run derived is cleared.
     * Optionally points the engagement at a different questionnaire response first.
     */
    @PostMapping("/{id}/rerun")
    @Operation(summary = "Regenerate an engagement's playbook, briefs and three directions")
    public EngagementController.EngagementSummary rerun(@PathVariable String id,
                                                       @RequestBody(required = false) RerunRequest body,
                                                       HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Engagement not found: " + id));
        if (e.getStatus() == Engagement.Status.PENDING || e.getStatus() == Engagement.Status.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Engagement is already " + e.getStatus());
        }

        if (body != null && body.questionnaireResponseId() != null && !body.questionnaireResponseId().isBlank()) {
            e.setQuestionnaireResponseId(body.questionnaireResponseId());
        }
        e.setPurposePlaybookJson(null);
        e.setBriefsJson(null);
        e.setResultsJson(null);
        e.setChosenDirection(null);
        e.setErrorMessage(null);
        e.setStatus(Engagement.Status.PENDING);
        e.setUpdatedAt(Instant.now());
        engagementRepo.save(e);

        log.info("Admin rerun of engagement {} (questionnaire response {})", id, e.getQuestionnaireResponseId());
        orchestrator.runEngagement(id);
        return EngagementController.EngagementSummary.of(e);
    }
}
