package ai.genesisbrands.service;

import ai.genesisbrands.agent.brandbook.BrandBookAgent;
import ai.genesisbrands.agent.brandbook.BrandBookInput;
import ai.genesisbrands.agent.brandbook.BrandBookOutput;
import ai.genesisbrands.agent.brandbook.BrandBookRefinementLoop;
import ai.genesisbrands.agent.copy.CopyAgent;
import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.copy.CopyRefinementLoop;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.logo.LogoAgent;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.logo.LogoRefinementLoop;
import ai.genesisbrands.agent.playbook.PlaybookAgent;
import ai.genesisbrands.agent.playbook.PlaybookInput;
import ai.genesisbrands.agent.playbook.PlaybookOutput;
import ai.genesisbrands.agent.playbook.PlaybookRefinementLoop;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EngagementOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(EngagementOrchestratorService.class);

    private final EngagementRepository engagementRepo;
    private final QuestionnaireResponseRepository responseRepo;
    private final QuestionnaireQuestionRepository questionRepo;
    private final QuestionnaireAnswerRepository answerRepo;
    private final BriefDerivationService briefDerivation;
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
    private final ObjectMapper objectMapper;

    @Async
    @Transactional
    public void runEngagement(String engagementId) {
        Engagement engagement = engagementRepo.findById(engagementId)
            .orElseThrow(() -> new RuntimeException("Engagement not found: " + engagementId));

        engagement.setStatus(Engagement.Status.RUNNING);
        engagement.setUpdatedAt(Instant.now());
        engagementRepo.save(engagement);

        try {
            // Load Q&A
            var response = responseRepo.findById(engagement.getQuestionnaireResponseId())
                .orElseThrow();
            List<QuestionnaireQuestion> questions =
                questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(response.getQuestionnaireId());
            List<QuestionnaireAnswer> answers =
                answerRepo.findByResponseIdOrderByCreatedAtAsc(engagement.getQuestionnaireResponseId());

            // Derive 3 direction briefs
            List<DirectionBrief> briefs = briefDerivation.derive(engagementId, questions, answers);
            engagement.setBriefsJson(objectMapper.writeValueAsString(briefs));
            engagementRepo.save(engagement);

            // Run pipeline for each direction
            List<DirectionOutput> directionOutputs = new ArrayList<>();
            for (DirectionBrief brief : briefs) {
                directionOutputs.add(runDirection(brief, engagement.isEvalMode()));
            }

            EngagementResults results = new EngagementResults(directionOutputs);
            engagement.setResultsJson(objectMapper.writeValueAsString(results));
            engagement.setStatus(Engagement.Status.DONE);
            engagement.setUpdatedAt(Instant.now());
            engagementRepo.save(engagement);

            log.info("Engagement {} completed successfully", engagementId);
        } catch (Exception e) {
            log.error("Engagement {} failed: {}", engagementId, e.getMessage(), e);
            engagement.setStatus(Engagement.Status.FAILED);
            engagement.setErrorMessage(e.getMessage());
            engagement.setUpdatedAt(Instant.now());
            engagementRepo.save(engagement);
        }
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

        return new DirectionOutput(brief.direction().name(), brief, copy, visual, logo, playbook, brandBook);
    }

    public record EngagementResults(List<DirectionOutput> directions) {}

    public record DirectionOutput(
        String direction,
        DirectionBrief brief,
        CopyOutput copy,
        VisualIdentityOutput visualIdentity,
        LogoOutput logo,
        PlaybookOutput playbook,
        BrandBookOutput brandBook
    ) {}
}
