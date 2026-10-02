package ai.genesisbrands.controller;

import ai.genesisbrands.agent.brandbook.BrandBookInput;
import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer;
import ai.genesisbrands.model.AgentRegenerationJob;
import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.AgentRegenerationJobRepository;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.AgentRegenerationService;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.EngagementOrchestratorService;
import ai.genesisbrands.service.PhotoSourcingService;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/engagements")
@RequiredArgsConstructor
public class EngagementController {

    private static final Logger log = LoggerFactory.getLogger(EngagementController.class);

    private final EngagementRepository engagementRepo;
    private final EngagementOrchestratorService orchestrator;
    private final BrandBookTemplateRenderer pdfRenderer;
    private final BlobStorageService blobStorageService;
    private final PhotoSourcingService photoSourcingService;
    private final ObjectMapper objectMapper;
    private final AdminAuthHelper adminAuth;
    private final ClientAuthHelper clientAuthHelper;
    private final AgentRegenerationJobRepository regenerationJobRepo;
    private final AgentRegenerationService regenerationService;

    private static final Set<String> REGENERATABLE_AGENT_IDS =
        Set.of("copy", "visual-identity", "logo", "playbook", "brand-book");

    // Per-(engagementId, direction) lock so the in-flight check and job insert below are
    // atomic. Without this, two near-simultaneous requests can both read "nothing in
    // flight" before either row is saved, both proceed, and race to read-modify-write the
    // same engagement's resultsJson — silently dropping one side's write. In-process only
    // (fine at current single-instance scale); revisit with a DB-level lock if this app is
    // ever horizontally scaled.
    private static final ConcurrentHashMap<String, Object> regenerationSlotLocks = new ConcurrentHashMap<>();

    @Value("${genesis.regenerate.max-per-slot:3}")
    private int maxRegeneratesPerSlot;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EngagementSummary create(@RequestBody CreateRequest req) {
        Engagement e = new Engagement();
        e.setId(UUID.randomUUID().toString());
        e.setSource(req.source() != null ? req.source() : Engagement.Source.CLIENT);
        e.setQuestionnaireResponseId(req.questionnaireResponseId());
        e.setEvalMode(Boolean.TRUE.equals(req.evalMode()));
        e.setClientEmail(req.clientEmail());
        e.setClientName(req.clientName());
        e.setClientUserId(req.clientUserId());
        engagementRepo.save(e);
        orchestrator.runEngagement(e.getId());
        return EngagementSummary.of(e);
    }

    // ── Import (admin only) ───────────────────────────────────────────────────

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public EngagementSummary importResults(@RequestBody ImportRequest req) {
        Engagement e = new Engagement();
        e.setId(UUID.randomUUID().toString());
        e.setSource(req.source() != null ? req.source() : Engagement.Source.SIMULATION);
        e.setClientEmail(req.clientEmail());
        e.setClientName(req.clientName());
        e.setStatus(Engagement.Status.DONE);
        try {
            e.setResultsJson(objectMapper.writeValueAsString(req.results()));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid results JSON: " + ex.getMessage());
        }
        return EngagementSummary.of(engagementRepo.save(e));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @GetMapping
    public List<EngagementSummary> list(@RequestParam(required = false) String source) {
        List<Engagement> rows = source != null
            ? engagementRepo.findAllBySourceOrderByCreatedAtDesc(Engagement.Source.valueOf(source.toUpperCase()))
            : engagementRepo.findAllByOrderByCreatedAtDesc();
        return rows.stream().map(EngagementSummary::of).toList();
    }

    @GetMapping("/mine")
    public List<EngagementSummary> mine(HttpServletRequest req) {
        String clientUserId = resolveClientUserId(req);
        if (clientUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        }
        return engagementRepo.findAllByClientUserIdOrderByCreatedAtDesc(clientUserId).stream()
            .map(EngagementSummary::of).toList();
    }

    @GetMapping("/{id}")
    public EngagementDetail get(@PathVariable String id, HttpServletRequest req) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));

        EngagementResults results = null;
        if (e.getResultsJson() != null) {
            try {
                results = objectMapper.readValue(e.getResultsJson(), EngagementResults.class);
            } catch (Exception ex) {
                // results stay null — client will see DONE status but no data
            }
        }

        boolean adminAccess = adminAuth.isAdminRequest(req);
        String clientUserId = resolveClientUserId(req);
        boolean ownsEngagement = clientUserId != null && clientUserId.equals(e.getClientUserId());
        boolean downloadAllowed = adminAccess || e.getPaymentStatus() == Engagement.PaymentStatus.PAID;

        return new EngagementDetail(EngagementSummary.of(e), results, adminAccess, downloadAllowed, ownsEngagement);
    }

    // ── Claim (attach an anonymous engagement to the signed-in client) ─────────

    @PostMapping("/{id}/claim")
    public EngagementSummary claim(@PathVariable String id, HttpServletRequest req) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));

        String clientUserId = resolveClientUserId(req);
        if (clientUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required to claim this engagement");
        }
        if (e.getClientUserId() != null && !e.getClientUserId().equals(clientUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This engagement is already linked to another account");
        }
        if (e.getClientUserId() == null) {
            e.setClientUserId(clientUserId);
            e.setUpdatedAt(Instant.now());
            engagementRepo.save(e);
        }
        return EngagementSummary.of(e);
    }

    // ── Direction choice (results page) ─────────────────────────────────────────

    @PostMapping("/{id}/choose-direction")
    public EngagementSummary chooseDirection(@PathVariable String id,
                                              @RequestBody ChooseDirectionRequest req,
                                              HttpServletRequest servletReq) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));
        requireOwner(e, servletReq);

        if (e.getStatus() != Engagement.Status.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Engagement is not finished yet");
        }
        DirectionOutput chosen = findDirection(e, req.direction());
        if (chosen == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown direction: " + req.direction());
        }
        e.setChosenDirection(chosen.direction().toUpperCase());
        e.setUpdatedAt(Instant.now());
        return EngagementSummary.of(engagementRepo.save(e));
    }

    // ── Watermarked preview (free, pre-payment) ─────────────────────────────────

    @GetMapping("/{id}/preview/{direction}")
    public ResponseEntity<byte[]> preview(@PathVariable String id,
                                           @PathVariable String direction,
                                           HttpServletRequest req) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));
        requireOwner(e, req);

        DirectionOutput dir = findDirection(e, direction);
        if (dir == null) return ResponseEntity.notFound().build();

        BrandBookInput input = new BrandBookInput(
            dir.brief(), dir.playbook(), dir.copy(), dir.visualIdentity(), dir.logo()
        );
        byte[] jpeg;
        try {
            jpeg = pdfRenderer.renderPreviewImage(input, dir.brandBook());
        } catch (Exception ex) {
            log.error("Preview render failed for engagement {} direction {}: {}", id, direction, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .body(jpeg);
    }

    private void requireOwner(Engagement e, HttpServletRequest req) {
        String clientUserId = resolveClientUserId(req);
        if (clientUserId == null || !clientUserId.equals(e.getClientUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the owner of this engagement");
        }
    }

    private DirectionOutput findDirection(Engagement e, String direction) {
        EngagementResults results;
        try {
            results = objectMapper.readValue(e.getResultsJson(), EngagementResults.class);
        } catch (Exception ex) {
            return null;
        }
        return results.directions().stream()
            .filter(d -> d.direction().equalsIgnoreCase(direction))
            .findFirst()
            .orElse(null);
    }

    // ── Agent regeneration (client-triggered, per agentId pluggable-agent view) ─

    @PostMapping("/{id}/agents/{agentId}/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RegenerationJobSummary regenerate(@PathVariable String id,
                                              @PathVariable String agentId,
                                              @RequestBody RegenerateRequest req,
                                              HttpServletRequest servletReq) {
        if (!REGENERATABLE_AGENT_IDS.contains(agentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent does not support regeneration: " + agentId);
        }
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));
        requireOwner(e, servletReq);

        if (e.getStatus() != Engagement.Status.DONE || e.getPaymentStatus() != Engagement.PaymentStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Engagement is not paid/finished yet");
        }
        if (findDirection(e, req.direction()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown direction: " + req.direction());
        }

        String direction = req.direction().toUpperCase();
        Object slotLock = regenerationSlotLocks.computeIfAbsent(id + ":" + direction, k -> new Object());
        AgentRegenerationJob job;
        synchronized (slotLock) {
            boolean inFlight = regenerationJobRepo.findFirstByEngagementIdAndDirectionAndStatusIn(
                id, direction,
                List.of(AgentRegenerationJob.Status.QUEUED, AgentRegenerationJob.Status.RUNNING)
            ).isPresent();
            if (inFlight) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A regeneration is already in progress for this direction");
            }

            long used = regenerationJobRepo.countByEngagementIdAndDirectionAndAgentId(id, direction, agentId);
            if (used >= maxRegeneratesPerSlot) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Regeneration limit reached for this agent (" + maxRegeneratesPerSlot + " max)");
            }

            job = new AgentRegenerationJob();
            job.setId(UUID.randomUUID().toString());
            job.setEngagementId(id);
            job.setDirection(direction);
            job.setAgentId(agentId);
            job.setFeedback(req.feedback());
            regenerationJobRepo.save(job);
        }

        regenerationService.regenerate(job.getId());

        return RegenerationJobSummary.of(job);
    }

    @GetMapping("/{id}/agents/regenerations/{jobId}")
    public RegenerationJobSummary regenerationStatus(@PathVariable String id,
                                                       @PathVariable String jobId,
                                                       HttpServletRequest req) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));
        requireOwner(e, req);

        AgentRegenerationJob job = regenerationJobRepo.findById(jobId)
            .orElseThrow(() -> new NoSuchElementException("Regeneration job not found: " + jobId));
        if (!job.getEngagementId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Job does not belong to this engagement");
        }
        return RegenerationJobSummary.of(job);
    }

    // ── Payment placeholder ───────────────────────────────────────────────────

    @PostMapping("/{id}/request-payment")
    public EngagementSummary requestPayment(@PathVariable String id, @RequestBody PaymentRequest req) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));
        if (e.getClientEmail() == null) e.setClientEmail(req.email());
        if (e.getClientName() == null) e.setClientName(req.name());
        e.setPaymentStatus(Engagement.PaymentStatus.REQUESTED);
        e.setUpdatedAt(Instant.now());
        return EngagementSummary.of(engagementRepo.save(e));
    }

    @PostMapping("/{id}/mark-paid")
    public EngagementSummary markPaid(@PathVariable String id) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));
        e.setPaymentStatus(Engagement.PaymentStatus.PAID);
        e.setPaidAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return EngagementSummary.of(engagementRepo.save(e));
    }

    // ── PDF download ──────────────────────────────────────────────────────────

    @GetMapping("/{id}/pdf/{direction}")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable String id,
                                               @PathVariable String direction,
                                               HttpServletRequest req) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));

        boolean adminAccess = adminAuth.isAdminRequest(req);
        if (!adminAccess && e.getPaymentStatus() != Engagement.PaymentStatus.PAID) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).build();
        }

        EngagementResults results;
        try {
            results = objectMapper.readValue(e.getResultsJson(), EngagementResults.class);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        DirectionOutput dir = results.directions().stream()
            .filter(d -> d.direction().equalsIgnoreCase(direction))
            .findFirst()
            .orElse(null);
        if (dir == null) return ResponseEntity.notFound().build();

        String safeName = dir.brief().brand().name().replaceAll("[^a-zA-Z0-9]+", "-");
        String filename = safeName + "-" + direction.toLowerCase() + "-brand-book.pdf";

        // Fast path: serve the PDF that was already rendered and stored by the pipeline.
        // Falls back to on-demand Playwright render for Playground sessions or if blob
        // storage wasn't configured when the engagement ran.
        if (dir.pdfBlobPath() != null) {
            try {
                byte[] stored = blobStorageService.download(dir.pdfBlobPath());
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(stored);
            } catch (Exception ex) {
                log.warn("Stored PDF not found at {} — re-rendering on demand: {}", dir.pdfBlobPath(), ex.getMessage());
            }
        }

        // Source photos before re-rendering so the PDF gets imagery even when blob
        // storage wasn't available when the engagement originally ran.
        // Falls back to brand-context keywords when imageKeywords is absent (older sessions).
        List<String> keywords = dir.visualIdentity() != null
            ? dir.visualIdentity().imageKeywords() : null;
        if (keywords == null || keywords.isEmpty()) {
            var brand = dir.brief() != null ? dir.brief().brand() : null;
            if (brand != null) {
                keywords = Stream.of(brand.industry(), brand.coreOffer(), brand.differentiator(), brand.tone())
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
            }
        }
        if (keywords != null && !keywords.isEmpty()) {
            try {
                photoSourcingService.fetchAndStore(dir.brief().engagementId(), keywords);
            } catch (Exception ex) {
                log.warn("Photo sourcing before re-render failed (non-fatal): {}", ex.getMessage());
            }
        }

        BrandBookInput input = new BrandBookInput(
            dir.brief(), dir.playbook(), dir.copy(), dir.visualIdentity(), dir.logo()
        );
        byte[] pdf;
        try {
            pdf = pdfRenderer.render(input, dir.brandBook());
        } catch (Exception ex) {
            log.error("On-demand PDF render failed for engagement {} direction {}: {}", id, direction, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(pdf);
    }

    @GetMapping("/{id}/logos/{direction}")
    public ResponseEntity<byte[]> downloadLogoZip(@PathVariable String id,
                                                   @PathVariable String direction,
                                                   HttpServletRequest req) {
        Engagement e = engagementRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + id));

        boolean adminAccess = adminAuth.isAdminRequest(req);
        if (!adminAccess && e.getPaymentStatus() != Engagement.PaymentStatus.PAID) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).build();
        }

        EngagementResults results;
        try {
            results = objectMapper.readValue(e.getResultsJson(), EngagementResults.class);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        DirectionOutput dir = results.directions().stream()
            .filter(d -> d.direction().equalsIgnoreCase(direction))
            .findFirst()
            .orElse(null);
        if (dir == null || dir.logoZipBlobPath() == null) return ResponseEntity.notFound().build();

        try {
            byte[] zip = blobStorageService.download(dir.logoZipBlobPath());
            String filename = dir.brief().brand().name().replaceAll("[^a-zA-Z0-9]+", "-")
                + "-" + direction.toLowerCase() + "-logos.zip";
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(zip);
        } catch (Exception ex) {
            log.warn("Logo ZIP not found at {}: {}", dir.logoZipBlobPath(), ex.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveClientUserId(HttpServletRequest req) {
        return clientAuthHelper.resolve(req).map(ClientUser::getId).orElse(null);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record CreateRequest(
        Engagement.Source source,
        String questionnaireResponseId,
        Boolean evalMode,
        String clientEmail,
        String clientName,
        String clientUserId
    ) {}

    public record PaymentRequest(String email, String name) {}

    public record ChooseDirectionRequest(String direction) {}

    public record RegenerateRequest(String direction, String feedback) {}

    public record RegenerationJobSummary(
        String id, String engagementId, String direction, String agentId,
        String status, String errorMessage, Instant createdAt, Instant completedAt
    ) {
        static RegenerationJobSummary of(AgentRegenerationJob job) {
            return new RegenerationJobSummary(
                job.getId(), job.getEngagementId(), job.getDirection(), job.getAgentId(),
                job.getStatus().name(), job.getErrorMessage(), job.getCreatedAt(), job.getCompletedAt()
            );
        }
    }

    public record ImportRequest(
        Engagement.Source source,
        String clientEmail,
        String clientName,
        EngagementResults results
    ) {}

    public record EngagementSummary(
        String id,
        String source,
        String status,
        String paymentStatus,
        String clientEmail,
        String clientName,
        String chosenDirection,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
    ) {
        static EngagementSummary of(Engagement e) {
            return new EngagementSummary(
                e.getId(), e.getSource().name(), e.getStatus().name(),
                e.getPaymentStatus().name(), e.getClientEmail(), e.getClientName(),
                e.getChosenDirection(), e.getErrorMessage(), e.getCreatedAt(), e.getUpdatedAt()
            );
        }
    }

    public record EngagementDetail(
        EngagementSummary summary,
        EngagementResults results,
        boolean adminAccess,
        boolean downloadAllowed,
        boolean ownsEngagement
    ) {}
}
