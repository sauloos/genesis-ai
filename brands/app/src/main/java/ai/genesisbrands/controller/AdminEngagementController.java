package ai.genesisbrands.controller;

import ai.genesisbrands.agent.logo.AdoptedLogos;
import ai.genesisbrands.agent.logo.ImageTypes;
import ai.genesisbrands.agent.logo.LogoAssessment;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.model.AgentRegenerationJob;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.AgentRegenerationService;
import ai.genesisbrands.service.EngagementOrchestratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.NoSuchElementException;

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
    private final AgentRegenerationService regenerationService;
    private final AdoptedLogos adoptedLogos;
    private final ObjectMapper objectMapper;

    private static final long MAX_LOGO_BYTES = 10L * 1024 * 1024;

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

    /**
     * Replaces one direction's logo and rebuilds what depends on it (Playbook, Brand Book, PDF,
     * logo package) in the background; the previous output is archived as a version. Either the
     * client's own logo ({@code client=true}, optionally with its {@code assessment} JSON), or a
     * logo from elsewhere ({@code logo}: LogoOutput JSON whose image fields are replaced by the
     * uploaded {@code primary} and optional {@code symbol}).
     */
    @PostMapping(value = "/{id}/directions/{direction}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Replace a direction's logo and rebuild its brand book")
    public AgentRegenerationJob replaceLogo(@PathVariable String id, @PathVariable String direction,
                                            @RequestPart("primary") MultipartFile primary,
                                            @RequestPart(value = "symbol", required = false) MultipartFile symbol,
                                            @RequestParam(value = "logo", required = false) String logoJson,
                                            @RequestParam(value = "client", defaultValue = "false") boolean client,
                                            @RequestParam(value = "assessment", required = false) String assessmentJson,
                                            HttpServletRequest req) throws IOException {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
        byte[] primaryBytes = png(primary, "primary");
        byte[] symbolBytes = symbol == null || symbol.isEmpty() ? null : png(symbol, "symbol");
        if (!client && (logoJson == null || logoJson.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send the logo JSON, or client=true");
        }

        AgentRegenerationJob job;
        try {
            job = regenerationService.queueAdminLogo(id, direction, client ? "client's own logo" : "admin-supplied logo");
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        try {
            LogoOutput logo;
            if (client) {
                LogoAssessment assessment = assessmentJson == null || assessmentJson.isBlank() ? null
                    : objectMapper.readValue(assessmentJson, LogoAssessment.class);
                logo = adoptedLogos.clientLogo(id, job.getDirection(), primaryBytes, assessment);
            } else {
                logo = adoptedLogos.adopt(objectMapper.readValue(logoJson, LogoOutput.class),
                    id, job.getDirection(), primaryBytes, symbolBytes);
            }
            log.info("Admin logo replacement for {} / {} queued as job {}", id, job.getDirection(), job.getId());
            regenerationService.applyAdminLogo(job.getId(), logo);
            return job;
        } catch (Exception e) {
            job.setStatus(AgentRegenerationJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            regenerationService.saveJob(job);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not use that logo: " + e.getMessage());
        }
    }

    public record RebuildRequest(String feedback) {}

    /**
     * Regenerates one agent's output for one direction of a finished engagement, then everything
     * downstream of it (Copy goes on to Playbook, Brand Book and PDF), keeping the logo and
     * palette. Applied straight in with the old output archived as a version. Poll
     * {@code /logo-jobs/{jobId}}.
     */
    @PostMapping("/{id}/directions/{direction}/agents/{agentId}/rebuild")
    @Operation(summary = "Regenerate an agent for a direction and rebuild everything downstream")
    public AgentRegenerationJob rebuild(@PathVariable String id, @PathVariable String direction,
                                        @PathVariable String agentId,
                                        @RequestBody(required = false) RebuildRequest body,
                                        HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
        AgentRegenerationJob job;
        try {
            job = regenerationService.queueAdminRebuild(id, direction, agentId, body == null ? null : body.feedback());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        log.info("Admin rebuild of {} / {} from {} queued as job {}", id, job.getDirection(), agentId, job.getId());
        regenerationService.applyAdminRebuild(job.getId(), agentId);
        return job;
    }

    @GetMapping("/logo-jobs/{jobId}")
    @Operation(summary = "Status of an admin logo replacement")
    public AgentRegenerationJob logoJob(@PathVariable String jobId, HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
        try {
            return regenerationService.adminJob(jobId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private static byte[] png(MultipartFile file, String name) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_LOGO_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send the " + name + " image, up to 10MB");
        }
        byte[] bytes = file.getBytes();
        if (!"image/png".equals(ImageTypes.sniff(bytes))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The " + name + " image must be a PNG");
        }
        return bytes;
    }
}
