package ai.genesisbrands.controller;

import ai.genesisbrands.agent.brandbook.BrandBookInput;
import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.ClientAuthFilter;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.ClientAuthService;
import ai.genesisbrands.service.EngagementOrchestratorService;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/engagements")
@RequiredArgsConstructor
public class EngagementController {

    private static final Logger log = LoggerFactory.getLogger(EngagementController.class);

    private final EngagementRepository engagementRepo;
    private final EngagementOrchestratorService orchestrator;
    private final BrandBookTemplateRenderer pdfRenderer;
    private final BlobStorageService blobStorageService;
    private final ObjectMapper objectMapper;
    private final AdminAuthHelper adminAuth;
    private final ClientAuthService clientAuthService;

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

    // ── Read ──────────────────────────────────────────────────────────────────

    @GetMapping
    public List<EngagementSummary> list(@RequestParam(required = false) String source) {
        List<Engagement> rows = source != null
            ? engagementRepo.findAllBySourceOrderByCreatedAtDesc(Engagement.Source.valueOf(source.toUpperCase()))
            : engagementRepo.findAllByOrderByCreatedAtDesc();
        return rows.stream().map(EngagementSummary::of).toList();
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

        BrandBookInput input = new BrandBookInput(
            dir.brief(), dir.playbook(), dir.copy(), dir.visualIdentity(), dir.logo()
        );
        byte[] pdf = pdfRenderer.render(input, dir.brandBook());
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
        String token = ClientAuthFilter.extractSessionCookie(req);
        return clientAuthService.validateSession(token)
            .map(ai.genesisbrands.model.ClientUser::getId)
            .orElse(null);
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

    public record EngagementSummary(
        String id,
        String source,
        String status,
        String paymentStatus,
        String clientEmail,
        String clientName,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
    ) {
        static EngagementSummary of(Engagement e) {
            return new EngagementSummary(
                e.getId(), e.getSource().name(), e.getStatus().name(),
                e.getPaymentStatus().name(), e.getClientEmail(), e.getClientName(),
                e.getErrorMessage(), e.getCreatedAt(), e.getUpdatedAt()
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
