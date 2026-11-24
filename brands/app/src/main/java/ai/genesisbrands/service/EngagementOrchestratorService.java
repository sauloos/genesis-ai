package ai.genesisbrands.service;

import ai.genesisbrands.agent.brandbook.BrandBookAgent;
import ai.genesisbrands.agent.brandbook.BrandBookInput;
import ai.genesisbrands.agent.brandbook.BrandBookOutput;
import ai.genesisbrands.agent.brandbook.BrandBookRefinementLoop;
import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer;
import ai.genesisbrands.agent.copy.CopyAgent;
import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.copy.CopyRefinementLoop;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.logo.LogoAgent;
import ai.genesisbrands.agent.logo.LogoExportService;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.logo.LogoRefinementLoop;
import ai.genesisbrands.agent.playbook.PlaybookAgent;
import ai.genesisbrands.agent.playbook.PlaybookInput;
import ai.genesisbrands.agent.playbook.PlaybookOutput;
import ai.genesisbrands.agent.playbook.PlaybookRefinementLoop;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookInput;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookInputFactory;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookOutput;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookRefinementLoop;
import ai.genesisbrands.agent.visualidentity.VisualIdentityAgent;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityRefinementLoop;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import ai.genesisbrands.repository.QuestionnaireResponseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EngagementOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(EngagementOrchestratorService.class);

    private final EngagementRepository engagementRepo;
    private final QuestionnaireResponseRepository responseRepo;
    private final QuestionnaireQuestionRepository questionRepo;
    private final QuestionnaireAnswerRepository answerRepo;
    private final BriefDerivationService briefDerivation;
    private final PurposePlaybookInputFactory purposePlaybookInputs;
    private final PurposePlaybookRefinementLoop purposePlaybookLoop;
    private final PurposePlaybookBriefDerivation purposePlaybookBriefs;
    private final EngagementPlaybookLayer2Ingest playbookIngest;
    private final CopyAgent copyAgent;
    private final CopyRefinementLoop copyLoop;
    private final VisualIdentityAgent visualAgent;
    private final VisualIdentityRefinementLoop visualLoop;
    private final LogoAgent logoAgent;
    private final LogoRefinementLoop logoLoop;
    private final PlaybookAgent playbookAgent;
    private final PlaybookRefinementLoop playbookLoop;
    private final BrandBookAgent brandBookAgent;
    private final BrandBookRefinementLoop brandBookLoop;
    private final BrandBookTemplateRenderer pdfRenderer;
    private final PhotoSourcingService photoSourcingService;
    private final LogoExportService logoExportService;
    private final BlobStorageService blobStorageService;
    private final ObjectMapper objectMapper;

    @Async
    public void runEngagement(String engagementId) {
        Engagement engagement = engagementRepo.findById(engagementId)
            .orElseThrow(() -> new RuntimeException("Engagement not found: " + engagementId));

        var response = responseRepo.findById(engagement.getQuestionnaireResponseId())
            .orElseThrow();
        List<QuestionnaireQuestion> questions =
            questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(response.getQuestionnaireId());
        List<QuestionnaireAnswer> answers =
            answerRepo.findByResponseIdOrderByCreatedAtAsc(engagement.getQuestionnaireResponseId());

        runEngagementCore(engagement, questions, answers);
    }

    /** Same pipeline as {@link #runEngagement(String)}, for callers that already have the Q&A in hand (e.g. a PageFlow's captured answers) instead of a QuestionnaireResponse to load. */
    @Async
    public void runEngagementWithAnswers(String engagementId, List<QuestionnaireQuestion> questions, List<QuestionnaireAnswer> answers) {
        Engagement engagement = engagementRepo.findById(engagementId)
            .orElseThrow(() -> new RuntimeException("Engagement not found: " + engagementId));

        runEngagementCore(engagement, questions, answers);
    }

    private void runEngagementCore(Engagement engagement, List<QuestionnaireQuestion> questions, List<QuestionnaireAnswer> answers) {
        String engagementId = engagement.getId();

        engagement.setStatus(Engagement.Status.RUNNING);
        engagement.setUpdatedAt(Instant.now());
        saveEngagement(engagement);

        try {
            // Write this engagement's Purpose Playbook, then derive the three directions from it.
            // Null when the questionnaire carries no Layer 1 signal keys — see derivePlaybook.
            PurposePlaybookOutput playbook = derivePlaybook(engagement, questions, answers);
            if (playbook != null) {
                // Stored before the briefs, on its own save. This document costs ten minutes of
                // model time and is the client's permanent context; losing it to a failure in the
                // derivation that follows would be the expensive half of the engagement thrown
                // away for the cheap half's bug.
                engagement.setPurposePlaybookJson(objectMapper.writeValueAsString(playbook));
                saveEngagement(engagement);
            }

            // Derive 3 direction briefs
            List<DirectionBrief> briefs = playbook != null
                ? purposePlaybookBriefs.derive(engagementId, playbook)
                : briefDerivation.derive(engagementId, questions, answers);
            engagement.setBriefsJson(objectMapper.writeValueAsString(briefs));
            saveEngagement(engagement);

            // Run pipeline for each direction
            List<DirectionOutput> directionOutputs = new ArrayList<>();
            for (DirectionBrief brief : briefs) {
                directionOutputs.add(runDirection(brief, engagement.isEvalMode()));
            }

            // Source photos once per engagement using merged imageKeywords from all directions.
            // Non-fatal: a missing Unsplash key or network error just leaves photo slots empty
            // (the PDF template falls back to the primary colour on those pages).
            List<String> mergedKeywords = directionOutputs.stream()
                .map(d -> d.visualIdentity().imageKeywords())
                .filter(k -> k != null)
                .flatMap(List::stream)
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
            try {
                photoSourcingService.fetchAndStore(engagementId, mergedKeywords);
            } catch (Exception e) {
                log.warn("Photo sourcing failed for engagement {} (non-fatal): {}", engagementId, e.getMessage());
            }

            // Render screen PDF and export logo ZIP for each direction.
            // Both are non-fatal: agent outputs are the primary record; derived assets
            // are best-effort and stored separately in blob.
            List<DirectionOutput> finalized = new ArrayList<>();
            for (DirectionOutput dir : directionOutputs) {
                String pdfBlobPath     = renderAndStorePdf(dir);
                String logoZipBlobPath = exportLogoPackage(dir);
                finalized.add(dir.withPdfBlobPath(pdfBlobPath).withLogoZipBlobPath(logoZipBlobPath));
            }

            EngagementResults results = new EngagementResults(finalized);
            engagement.setResultsJson(objectMapper.writeValueAsString(results));
            engagement.setStatus(Engagement.Status.DONE);
            engagement.setUpdatedAt(Instant.now());
            saveEngagement(engagement);

            log.info("Engagement {} completed successfully", engagementId);

            // Layer 2 ingestion on completion, as the architecture has always specified. Last and
            // non-fatal by design: the engagement is already saved as DONE, and project memory for
            // future clients is not worth marking this client's finished work as failed for.
            playbookIngest.ingest(engagement);
        } catch (Exception e) {
            log.error("Engagement {} failed: {}", engagementId, e.getMessage(), e);
            engagement.setStatus(Engagement.Status.FAILED);
            engagement.setErrorMessage(e.getMessage());
            engagement.setUpdatedAt(Instant.now());
            saveEngagement(engagement);
        }
    }

    /**
     * This engagement's Purpose Playbook, or null when the questionnaire cannot produce one.
     *
     * <p>The test is whether the questions carry Layer 1 signal keys, and it is not a convenience.
     * Signal keys are the contract between the questionnaire, the reasoning modules and the
     * playbook: a questionnaire without them hands the agent answers under no name any module
     * recognises, and the agent would write a fluent, fully-sourced document about nothing. Every
     * engagement taken before the Purpose Playbook instrument existed is in exactly that position,
     * so those keep the legacy question-and-answer derivation, which is what they were built on.
     *
     * <p>Which question set is read depends on where the answers came from. An engagement with a
     * stored response is read through the factory, which resolves the <em>active</em> questions —
     * the ones a condition actually let through — because a question gated away was never asked
     * and must not be reported as skipped. A PageFlow engagement has no stored response (see
     * {@link PageFlowEngagementAdapter}) and its captured questions are already the asked set.
     */
    private PurposePlaybookOutput derivePlaybook(Engagement engagement,
                                                 List<QuestionnaireQuestion> questions,
                                                 List<QuestionnaireAnswer> answers) {
        String engagementId = engagement.getId();
        String responseId = engagement.getQuestionnaireResponseId();

        PurposePlaybookInput input = responseId != null && !responseId.isBlank()
            ? purposePlaybookInputs.forResponse(responseId, engagementId, null, null)
            : purposePlaybookInputs.from(engagementId, questions, answers, null, null);

        if (input.expectedSignals().isEmpty()) {
            log.info("Engagement {} ran on a questionnaire with no Layer 1 signal keys — deriving "
                + "briefs from the raw answers instead of a Purpose Playbook", engagementId);
            return null;
        }

        log.info("Writing the Purpose Playbook for engagement {} ({} signals answered of {} asked)",
            engagementId, input.signals().size(), input.expectedSignals().size());
        var result = purposePlaybookLoop.run(input);
        PurposePlaybookOutput playbook = result.output();
        log.info("Purpose Playbook for engagement {}: {} sections, accepted on iteration {}",
            engagementId, playbook.sections().size(), playbook.iteration());
        return playbook;
    }

    /**
     * The pipeline holds one in-memory {@code Engagement} for its whole (multi-minute) async run,
     * but the client can concurrently claim it or request payment via separate requests. A plain
     * save() here would silently overwrite those with the stale values this object was loaded
     * with, so every save re-syncs the client-owned fields from the current DB row first.
     */
    private void saveEngagement(Engagement engagement) {
        engagementRepo.findById(engagement.getId()).ifPresent(current -> {
            engagement.setClientUserId(current.getClientUserId());
            engagement.setClientEmail(current.getClientEmail());
            engagement.setClientName(current.getClientName());
            engagement.setPaymentStatus(current.getPaymentStatus());
            engagement.setPaidAt(current.getPaidAt());
        });
        engagementRepo.save(engagement);
    }

    private DirectionOutput runDirection(DirectionBrief brief, boolean evalMode) {
        log.info("Running {} direction for engagement {}", brief.direction(), brief.engagementId());

        CopyOutput copy = evalMode
            ? copyLoop.run(brief).output()
            : copyAgent.execute(brief);

        VisualIdentityOutput visual = evalMode
            ? visualLoop.run(brief).output()
            : visualAgent.execute(brief);

        LogoOutput logo = evalMode
            ? logoLoop.run(brief, LogoOutput.Method.DALLE).output()
            : logoAgent.execute(brief, LogoOutput.Method.DALLE);

        PlaybookInput playbookIn = new PlaybookInput(brief, copy, visual, logo);
        PlaybookOutput playbook = evalMode
            ? playbookLoop.run(playbookIn).output()
            : playbookAgent.execute(playbookIn);

        BrandBookInput bbIn = new BrandBookInput(brief, playbook, copy, visual, logo);
        BrandBookOutput brandBook = evalMode
            ? brandBookLoop.run(bbIn).output()
            : brandBookAgent.execute(bbIn);

        return new DirectionOutput(brief.direction().name(), brief, copy, visual, logo, playbook, brandBook, null, null);
    }

    private String exportLogoPackage(DirectionOutput dir) {
        VisualIdentityOutput visual = dir.visualIdentity();
        String primaryHex = visual.colorPalette() != null && !visual.colorPalette().isEmpty()
            ? visual.colorPalette().get(0).hex()
            : "#000000";
        return logoExportService.packageLogos(
            dir.brief().engagementId(), dir.direction(), dir.logo(), primaryHex, UUID.randomUUID().toString());
    }

    private String renderAndStorePdf(DirectionOutput dir) {
        try {
            BrandBookInput input = new BrandBookInput(
                dir.brief(), dir.playbook(), dir.copy(), dir.visualIdentity(), dir.logo());
            byte[] pdf = pdfRenderer.render(input, dir.brandBook());
            String blobPath = "assets/brand-books/%s/%s-%s.pdf".formatted(
                dir.brief().engagementId(), dir.direction().toLowerCase(), UUID.randomUUID());
            blobStorageService.upload(blobPath, pdf);
            log.info("Brand book PDF stored at {} for engagement {}",
                blobPath, dir.brief().engagementId());
            return blobPath;
        } catch (Exception e) {
            log.warn("PDF render failed for {} direction of engagement {} (non-fatal): {}",
                dir.direction(), dir.brief().engagementId(), e.getMessage());
            return null;
        }
    }

    public record EngagementResults(List<DirectionOutput> directions) {}

    public record DirectionOutput(
        String direction,
        DirectionBrief brief,
        CopyOutput copy,
        VisualIdentityOutput visualIdentity,
        LogoOutput logo,
        PlaybookOutput playbook,
        BrandBookOutput brandBook,
        String pdfBlobPath,
        String logoZipBlobPath
    ) {
        DirectionOutput withPdfBlobPath(String path) {
            return new DirectionOutput(direction, brief, copy, visualIdentity, logo, playbook, brandBook, path, logoZipBlobPath);
        }
        DirectionOutput withLogoZipBlobPath(String path) {
            return new DirectionOutput(direction, brief, copy, visualIdentity, logo, playbook, brandBook, pdfBlobPath, path);
        }
    }
}
