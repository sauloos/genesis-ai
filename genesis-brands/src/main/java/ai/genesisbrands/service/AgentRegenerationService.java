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
import ai.genesisbrands.repository.AgentRegenerationJobRepository;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-triggered regeneration of a single SLOT agent's output within an already-DONE
 * engagement, cascading to whatever agents depend on it (see the dependency table in
 * EngagementOrchestratorService's javadoc history / CLAUDE.md's Agent Pluggable
 * Architecture section) and re-rendering the PDF / Logo ZIP so download links keep working.
 * Mirrors EngagementOrchestratorService.runEngagement's async + read-modify-write pattern,
 * but acts on one direction's slot instead of running the whole pipeline.
 */
@Service
@RequiredArgsConstructor
public class AgentRegenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegenerationService.class);

    private final AgentRegenerationJobRepository jobRepo;
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

    @Async
    public void regenerate(String jobId) {
        AgentRegenerationJob job = jobRepo.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Regeneration job not found: " + jobId));

        job.setStatus(AgentRegenerationJob.Status.RUNNING);
        jobRepo.save(job);

        try {
            Engagement engagement = engagementRepo.findById(job.getEngagementId())
                .orElseThrow(() -> new RuntimeException("Engagement not found: " + job.getEngagementId()));

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

            AgentRevision revision = new AgentRevision(job.getFeedback(), List.of(), List.of());
            DirectionOutput updated = regenerateSlot(directions.get(idx), job.getAgentId(), revision);
            directions.set(idx, updated);

            engagement.setResultsJson(objectMapper.writeValueAsString(new EngagementResults(directions)));
            engagement.setUpdatedAt(Instant.now());
            saveEngagement(engagement);

            job.setStatus(AgentRegenerationJob.Status.DONE);
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);

            log.info("Regeneration job {} ({} / {} / {}) completed", jobId, job.getEngagementId(), job.getDirection(), job.getAgentId());
        } catch (Exception e) {
            log.error("Regeneration job {} failed: {}", jobId, e.getMessage(), e);
            job.setStatus(AgentRegenerationJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);
        }
    }

    private DirectionOutput regenerateSlot(DirectionOutput dir, String agentId, AgentRevision revision) {
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

        String pdfBlobPath = renderAndStorePdf(rebuilt);
        rebuilt = rebuilt.withPdfBlobPath(pdfBlobPath);

        if (logoZipAffected) {
            String logoZipBlobPath = exportLogoPackage(rebuilt);
            rebuilt = rebuilt.withLogoZipBlobPath(logoZipBlobPath);
        }

        return rebuilt;
    }

    private String renderAndStorePdf(DirectionOutput dir) {
        try {
            BrandBookInput input = new BrandBookInput(
                dir.brief(), dir.playbook(), dir.copy(), dir.visualIdentity(), dir.logo());
            byte[] pdf = pdfRenderer.render(input, dir.brandBook());
            String blobPath = "assets/brand-books/%s/%s.pdf".formatted(
                dir.brief().engagementId(), dir.direction().toLowerCase());
            blobStorageService.upload(blobPath, pdf);
            return blobPath;
        } catch (Exception e) {
            log.warn("PDF re-render failed for {} direction of engagement {} (non-fatal): {}",
                dir.direction(), dir.brief().engagementId(), e.getMessage());
            return dir.pdfBlobPath();
        }
    }

    private String exportLogoPackage(DirectionOutput dir) {
        VisualIdentityOutput visual = dir.visualIdentity();
        String primaryHex = visual.colorPalette() != null && !visual.colorPalette().isEmpty()
            ? visual.colorPalette().get(0).hex()
            : "#000000";
        return logoExportService.packageLogos(
            dir.brief().engagementId(), dir.direction(), dir.logo(), primaryHex);
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
