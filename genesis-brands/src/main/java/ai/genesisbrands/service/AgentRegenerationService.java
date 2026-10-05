package ai.genesisbrands.service;

import ai.genesisbrands.agent.brandbook.BrandBookAgent;
import ai.genesisbrands.agent.brandbook.BrandBookInput;
import ai.genesisbrands.agent.brandbook.BrandBookOutput;
import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer;
import ai.genesisbrands.agent.copy.CopyAgent;
import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.logo.LogoAgent;
import ai.genesisbrands.agent.logo.LogoExportService;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.playbook.PlaybookAgent;
import ai.genesisbrands.agent.playbook.PlaybookInput;
import ai.genesisbrands.agent.playbook.PlaybookOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityAgent;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;
import ai.genesisbrands.model.AgentRegenerationJob;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.model.EngagementDirectionVersion;
import ai.genesisbrands.repository.AgentRegenerationJobRepository;
import ai.genesisbrands.repository.EngagementDirectionVersionRepository;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-triggered regeneration of a single SLOT agent's output within an already-DONE
 * engagement, cascading to whatever agents depend on it (see the dependency table in
 * EngagementOrchestratorService's javadoc history / CLAUDE.md's Agent Pluggable
 * Architecture section) and re-rendering the PDF / Logo ZIP so download links keep working.
 *
 * Regeneration is a draft/approve/reject flow, not an immediate write: {@link #regenerate}
 * computes the new output and cascade but leaves {@code Engagement.resultsJson} untouched,
 * parking the draft on the job (AWAITING_APPROVAL) until the client calls {@link #approve}
 * (which archives the current output as a version and swaps in the draft) or {@link #reject}
 * (which discards the draft — nothing about the engagement changes).
 */
@Service
@RequiredArgsConstructor
public class AgentRegenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegenerationService.class);

    private static final Set<String> REGENERATABLE_AGENT_IDS =
        Set.of("copy", "visual-identity", "logo", "playbook", "brand-book");

    private final AgentRegenerationJobRepository jobRepo;
    private final EngagementDirectionVersionRepository versionRepo;
    private final EngagementRepository engagementRepo;
    private final CopyAgent copyAgent;
    private final VisualIdentityAgent visualAgent;
    private final LogoAgent logoAgent;
    private final PlaybookAgent playbookAgent;
    private final BrandBookAgent brandBookAgent;
    private final BrandBookTemplateRenderer pdfRenderer;
    private final LogoExportService logoExportService;
    private final BlobStorageService blobStorageService;
    private final ObjectMapper objectMapper;

    @Value("${genesis.regenerate.max-per-slot:3}")
    private int maxRegeneratesPerSlot;

    // Per-(engagementId, direction) lock so the in-flight check and job insert below are
    // atomic. Without this, two near-simultaneous requests can both read "nothing in
    // flight" before either row is saved, both proceed, and race to read-modify-write the
    // same engagement's resultsJson — silently dropping one side's write. In-process only
    // (fine at current single-instance scale); revisit with a DB-level lock if this app is
    // ever horizontally scaled.
    private final ConcurrentHashMap<String, Object> regenerationSlotLocks = new ConcurrentHashMap<>();

    /**
     * Validates ownership, engagement readiness, the per-slot regeneration cap, and the
     * in-flight lock, then queues a job and kicks off async generation of its draft. This is
     * the single entry point for requesting a regeneration — called by the Live Dashboard
     * REST endpoint and, eventually, by the Consultant chat's regeneration tools — so both
     * paths are gated identically.
     */
    public AgentRegenerationJob requestRegeneration(String engagementId, String direction, String agentId,
                                                      String feedback, String callerClientUserId) {
        if (!REGENERATABLE_AGENT_IDS.contains(agentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent does not support regeneration: " + agentId);
        }
        Engagement engagement = engagementRepo.findById(engagementId)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + engagementId));
        requireOwner(engagement, callerClientUserId);

        if (engagement.getStatus() != Engagement.Status.DONE || engagement.getPaymentStatus() != Engagement.PaymentStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Engagement is not paid/finished yet");
        }
        String normalizedDirection = direction.toUpperCase();
        if (findDirectionOutput(engagement, normalizedDirection) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown direction: " + direction);
        }

        Object slotLock = regenerationSlotLocks.computeIfAbsent(engagementId + ":" + normalizedDirection, k -> new Object());
        AgentRegenerationJob job;
        synchronized (slotLock) {
            boolean inFlight = jobRepo.findFirstByEngagementIdAndDirectionAndStatusIn(
                engagementId, normalizedDirection,
                List.of(AgentRegenerationJob.Status.QUEUED, AgentRegenerationJob.Status.RUNNING)
            ).isPresent();
            if (inFlight) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A regeneration is already in progress for this direction");
            }

            long used = jobRepo.countByEngagementIdAndDirectionAndAgentId(engagementId, normalizedDirection, agentId);
            if (used >= maxRegeneratesPerSlot) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Regeneration limit reached for this agent (" + maxRegeneratesPerSlot + " max)");
            }

            job = new AgentRegenerationJob();
            job.setId(UUID.randomUUID().toString());
            job.setEngagementId(engagementId);
            job.setDirection(normalizedDirection);
            job.setAgentId(agentId);
            job.setFeedback(feedback);
            jobRepo.save(job);
        }

        regenerate(job.getId());
        return job;
    }

    @Async
    public void regenerate(String jobId) {
        AgentRegenerationJob job = jobRepo.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Regeneration job not found: " + jobId));

        job.setStatus(AgentRegenerationJob.Status.RUNNING);
        jobRepo.save(job);

        try {
            Engagement engagement = engagementRepo.findById(job.getEngagementId())
                .orElseThrow(() -> new RuntimeException("Engagement not found: " + job.getEngagementId()));

            DirectionOutput current = findDirectionOutput(engagement, job.getDirection());
            if (current == null) {
                throw new RuntimeException("Direction not found: " + job.getDirection());
            }

            AgentRevision revision = new AgentRevision(job.getFeedback(), List.of(), List.of());
            DirectionOutput draft = regenerateSlot(current, job.getAgentId(), revision, job.getId());

            // Deliberately NOT written to Engagement.resultsJson — the draft sits on the job
            // until approve()/reject() decides its fate, so current docs stay untouched.
            job.setDraftOutputJson(objectMapper.writeValueAsString(draft));
            job.setStatus(AgentRegenerationJob.Status.AWAITING_APPROVAL);
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);

            log.info("Regeneration job {} ({} / {} / {}) draft ready, awaiting approval",
                jobId, job.getEngagementId(), job.getDirection(), job.getAgentId());
        } catch (Exception e) {
            log.error("Regeneration job {} failed: {}", jobId, e.getMessage(), e);
            job.setStatus(AgentRegenerationJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);
        }
    }

    /** Merges an awaiting-approval job's draft into the engagement, archiving the output it
     *  replaces as a new version first so it's never lost. */
    public AgentRegenerationJob approve(String jobId, String callerClientUserId) {
        AgentRegenerationJob job = jobRepo.findById(jobId)
            .orElseThrow(() -> new NoSuchElementException("Regeneration job not found: " + jobId));
        Engagement engagement = engagementRepo.findById(job.getEngagementId())
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + job.getEngagementId()));
        requireOwner(engagement, callerClientUserId);
        requireAwaitingApproval(job);

        try {
            DirectionOutput draft = objectMapper.readValue(job.getDraftOutputJson(), DirectionOutput.class);

            EngagementResults results = objectMapper.readValue(engagement.getResultsJson(), EngagementResults.class);
            List<DirectionOutput> directions = new ArrayList<>(results.directions());
            int idx = -1;
            for (int i = 0; i < directions.size(); i++) {
                if (directions.get(i).direction().equalsIgnoreCase(job.getDirection())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new RuntimeException("Direction not found: " + job.getDirection());
            }

            archiveVersion(job.getEngagementId(), job.getDirection(), directions.get(idx), job.getId());

            directions.set(idx, draft);
            engagement.setResultsJson(objectMapper.writeValueAsString(new EngagementResults(directions)));
            engagement.setUpdatedAt(Instant.now());
            saveEngagement(engagement);

            job.setStatus(AgentRegenerationJob.Status.DONE);
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);

            log.info("Regeneration job {} approved — {} direction of engagement {} updated",
                jobId, job.getDirection(), job.getEngagementId());
            return job;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to approve regeneration job " + jobId + ": " + e.getMessage(), e);
        }
    }

    /** Discards an awaiting-approval job's draft. No engagement mutation — current output
     *  stays exactly as it was. */
    public AgentRegenerationJob reject(String jobId, String callerClientUserId) {
        AgentRegenerationJob job = jobRepo.findById(jobId)
            .orElseThrow(() -> new NoSuchElementException("Regeneration job not found: " + jobId));
        Engagement engagement = engagementRepo.findById(job.getEngagementId())
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + job.getEngagementId()));
        requireOwner(engagement, callerClientUserId);
        requireAwaitingApproval(job);

        job.setStatus(AgentRegenerationJob.Status.REJECTED);
        job.setCompletedAt(Instant.now());
        jobRepo.save(job);

        log.info("Regeneration job {} rejected — {} direction of engagement {} left untouched",
            jobId, job.getDirection(), job.getEngagementId());
        return job;
    }

    private void requireOwner(Engagement engagement, String callerClientUserId) {
        if (callerClientUserId == null || !callerClientUserId.equals(engagement.getClientUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the owner of this engagement");
        }
    }

    private void requireAwaitingApproval(AgentRegenerationJob job) {
        if (job.getStatus() != AgentRegenerationJob.Status.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not awaiting approval");
        }
    }

    private DirectionOutput findDirectionOutput(Engagement engagement, String direction) {
        try {
            EngagementResults results = objectMapper.readValue(engagement.getResultsJson(), EngagementResults.class);
            return results.directions().stream()
                .filter(d -> d.direction().equalsIgnoreCase(direction))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void archiveVersion(String engagementId, String direction, DirectionOutput current, String jobId) {
        try {
            int nextVersion = (int) versionRepo.countByEngagementIdAndDirection(engagementId, direction) + 1;
            EngagementDirectionVersion version = new EngagementDirectionVersion();
            version.setId(UUID.randomUUID().toString());
            version.setEngagementId(engagementId);
            version.setDirection(direction);
            version.setVersionNumber(nextVersion);
            version.setDirectionOutputJson(objectMapper.writeValueAsString(current));
            version.setCreatedByJobId(jobId);
            versionRepo.save(version);
        } catch (Exception e) {
            throw new RuntimeException("Failed to archive version for " + engagementId + "/" + direction + ": " + e.getMessage(), e);
        }
    }

    private DirectionOutput regenerateSlot(DirectionOutput dir, String agentId, AgentRevision revision, String generationId) {
        CopyOutput copy = dir.copy();
        VisualIdentityOutput visual = dir.visualIdentity();
        LogoOutput logo = dir.logo();
        PlaybookOutput playbook = dir.playbook();
        BrandBookOutput brandBook = dir.brandBook();
        boolean logoZipAffected = false;

        switch (agentId) {
            case "copy" -> copy = copyAgent.executeWithRevision(dir.brief(), copy, revision);
            case "visual-identity" -> {
                visual = visualAgent.executeWithRevision(dir.brief(), visual, revision);
                logoZipAffected = true;
            }
            case "logo" -> {
                logo = logoAgent.executeWithRevision(dir.brief(), logo, revision);
                logoZipAffected = true;
            }
            case "playbook" -> playbook = playbookAgent.executeWithRevision(
                new PlaybookInput(dir.brief(), copy, visual, logo), playbook, revision);
            case "brand-book" -> brandBook = brandBookAgent.executeWithRevision(
                new BrandBookInput(dir.brief(), playbook, copy, visual, logo), brandBook, revision);
            default -> throw new IllegalArgumentException("Agent does not support regeneration: " + agentId);
        }

        // Cascade: anything downstream of the regenerated agent must re-run with the new input.
        if (agentId.equals("copy") || agentId.equals("visual-identity") || agentId.equals("logo")) {
            playbook = playbookAgent.execute(new PlaybookInput(dir.brief(), copy, visual, logo));
        }
        if (!agentId.equals("brand-book")) {
            brandBook = brandBookAgent.execute(new BrandBookInput(dir.brief(), playbook, copy, visual, logo));
        }

        DirectionOutput rebuilt = new DirectionOutput(
            dir.direction(), dir.brief(), copy, visual, logo, playbook, brandBook,
            dir.pdfBlobPath(), dir.logoZipBlobPath());

        String pdfBlobPath = renderAndStorePdf(rebuilt, generationId);
        rebuilt = rebuilt.withPdfBlobPath(pdfBlobPath);

        if (logoZipAffected) {
            String logoZipBlobPath = exportLogoPackage(rebuilt, generationId);
            rebuilt = rebuilt.withLogoZipBlobPath(logoZipBlobPath);
        }

        return rebuilt;
    }

    private String renderAndStorePdf(DirectionOutput dir, String generationId) {
        try {
            BrandBookInput input = new BrandBookInput(
                dir.brief(), dir.playbook(), dir.copy(), dir.visualIdentity(), dir.logo());
            byte[] pdf = pdfRenderer.render(input, dir.brandBook());
            String blobPath = "assets/brand-books/%s/%s-%s.pdf".formatted(
                dir.brief().engagementId(), dir.direction().toLowerCase(), generationId);
            blobStorageService.upload(blobPath, pdf);
            return blobPath;
        } catch (Exception e) {
            log.warn("PDF re-render failed for {} direction of engagement {} (non-fatal): {}",
                dir.direction(), dir.brief().engagementId(), e.getMessage());
            return dir.pdfBlobPath();
        }
    }

    private String exportLogoPackage(DirectionOutput dir, String generationId) {
        VisualIdentityOutput visual = dir.visualIdentity();
        String primaryHex = visual.colorPalette() != null && !visual.colorPalette().isEmpty()
            ? visual.colorPalette().get(0).hex()
            : "#000000";
        return logoExportService.packageLogos(
            dir.brief().engagementId(), dir.direction(), dir.logo(), primaryHex, generationId);
    }

    /** Same race-condition-avoidance as EngagementOrchestratorService.saveEngagement: re-sync
     *  client-owned fields from the current DB row so this read-modify-write of resultsJson
     *  doesn't stomp a concurrent client-side update (claim, payment, direction choice). */
    private void saveEngagement(Engagement engagement) {
        engagementRepo.findById(engagement.getId()).ifPresent(current -> {
            engagement.setClientUserId(current.getClientUserId());
            engagement.setClientEmail(current.getClientEmail());
            engagement.setClientName(current.getClientName());
            engagement.setPaymentStatus(current.getPaymentStatus());
            engagement.setPaidAt(current.getPaidAt());
            engagement.setChosenDirection(current.getChosenDirection());
        });
        engagementRepo.save(engagement);
    }
}
