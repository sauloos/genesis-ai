package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.model.Brand;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.repository.BrandRepository;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import ai.genesisbrands.repository.QuestionnaireResponseRepository;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Genesis Brands' {@link ConsultantSubjectProvider}: a consultant conversation is scoped
 * to a {@link Brand} (the admin/Playground "pure consultant" sandbox), or — for the
 * customer-facing chat — to a client's own {@link Engagement}, addressed by the synthetic
 * id {@code "engagement:" + engagementId} (see {@code ClientConsultantController}, the only
 * caller that ever constructs such an id; it is never accepted from a client request).
 * Its presence as a bean is what turns on {@code ConsultantService}
 * (see {@code ConsultantServiceConfiguration} in brain-engine-core).
 */
@Component
public class BrandConsultantSubjectProvider implements ConsultantSubjectProvider {

    private static final String ENGAGEMENT_PREFIX = "engagement:";

    private final BrandRepository brandRepo;
    private final EngagementRepository engagementRepo;
    private final QuestionnaireResponseRepository questionnaireResponseRepo;
    private final QuestionnaireQuestionRepository questionnaireQuestionRepo;
    private final QuestionnaireAnswerRepository questionnaireAnswerRepo;
    private final ObjectMapper objectMapper;
    private final PurposePlaybookContextService playbookContext;

    public BrandConsultantSubjectProvider(BrandRepository brandRepo, EngagementRepository engagementRepo,
                                           QuestionnaireResponseRepository questionnaireResponseRepo,
                                           QuestionnaireQuestionRepository questionnaireQuestionRepo,
                                           QuestionnaireAnswerRepository questionnaireAnswerRepo,
                                           ObjectMapper objectMapper,
                                           PurposePlaybookContextService playbookContext) {
        this.playbookContext = playbookContext;
        this.brandRepo = brandRepo;
        this.engagementRepo = engagementRepo;
        this.questionnaireResponseRepo = questionnaireResponseRepo;
        this.questionnaireQuestionRepo = questionnaireQuestionRepo;
        this.questionnaireAnswerRepo = questionnaireAnswerRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConsultantSubject find(String subjectId) {
        if (subjectId.startsWith(ENGAGEMENT_PREFIX)) {
            return findFromEngagement(subjectId.substring(ENGAGEMENT_PREFIX.length()));
        }
        Brand brand = brandRepo.findById(subjectId)
            .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + subjectId));
        return new ConsultantSubject(brand.getId(), brand.getName(), brand.getIndustry(), brand.getAudience(), brand.getBrief());
    }

    /**
     * Reduces a client's own chosen-direction output into a {@link ConsultantSubject},
     * grounding the customer-aware chat in their real tagline/tone/palette/logo/playbook
     * instead of an arbitrary Brand sandbox row.
     */
    private ConsultantSubject findFromEngagement(String engagementId) {
        Engagement e = engagementRepo.findById(engagementId)
            .orElseThrow(() -> new IllegalArgumentException("Engagement not found: " + engagementId));

        DirectionOutput dir = chosenDirection(e).orElse(null);
        if (dir == null) {
            return beforeADirectionIsChosen(e);
        }
        var brand = dir.brief().brand();
        return new ConsultantSubject(
            ENGAGEMENT_PREFIX + engagementId,
            brand.name(), brand.industry(), brand.targetAudience(),
            buildDiscoveryContext(e) + buildBrief(dir)
        );
    }

    /** The chosen direction's output, or empty while the engagement has no chosen direction. */
    private Optional<DirectionOutput> chosenDirection(Engagement e) {
        if (e.getResultsJson() == null || e.getResultsJson().isBlank() || e.getChosenDirection() == null) {
            return Optional.empty();
        }
        EngagementResults results;
        try {
            results = objectMapper.readValue(e.getResultsJson(), EngagementResults.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not parse results for engagement: " + e.getId(), ex);
        }
        return results.directions().stream()
            .filter(d -> d.direction().equalsIgnoreCase(e.getChosenDirection()))
            .findFirst();
    }

    /**
     * A subject for a client whose engagement is still in flight: the playbook is written, the
     * directions are not chosen, and there is no specialist output to describe yet.
     *
     * <p>Worth having rather than failing. The discovery context — their Purpose Playbook — is the
     * substance of what the consultant needs, and it exists from the moment discovery finishes.
     * What is missing is a chosen identity, so the name is taken from the derived briefs if they
     * exist and otherwise said to be unestablished, never guessed: this string is read out to the
     * client as their brand's name.
     */
    private ConsultantSubject beforeADirectionIsChosen(Engagement e) {
        DirectionBrief.BrandContext brand = firstBriefBrand(e).orElse(null);
        return new ConsultantSubject(
            ENGAGEMENT_PREFIX + e.getId(),
            brand == null || brand.name() == null || brand.name().isBlank()
                ? "(no brand name established yet)" : brand.name(),
            brand == null ? null : brand.industry(),
            brand == null ? null : brand.targetAudience(),
            buildDiscoveryContext(e)
        );
    }

    private Optional<DirectionBrief.BrandContext> firstBriefBrand(Engagement e) {
        if (e.getBriefsJson() == null || e.getBriefsJson().isBlank()) {
            return Optional.empty();
        }
        try {
            List<DirectionBrief> briefs = objectMapper.readValue(
                e.getBriefsJson(), new TypeReference<List<DirectionBrief>>() {});
            return briefs.isEmpty() ? Optional.empty() : Optional.ofNullable(briefs.get(0).brand());
        } catch (Exception ex) {
            // The briefs are only being read here for a display name; an unreadable one is not a
            // reason to deny the client a consultant that has their playbook.
            return Optional.empty();
        }
    }

    /**
     * What the client said in discovery, settled rather than raw where possible.
     *
     * <p>The playbook wins over the transcript whenever the engagement has one. They are not two
     * renderings of the same material: the transcript is 131 unreconciled answers, including every
     * contradiction the client gave and every question they clicked past, and a consultant reading
     * it has to re-derive the client's position on the fly, differently each turn. The playbook is
     * that derivation, done once and agreed, with a confidence map saying which parts are thin.
     * Reading the transcript instead would reintroduce exactly the unresolved material the playbook
     * exists to settle — and it is the bigger of the two.
     *
     * <p>The transcript remains the fallback for engagements that ran before the instrument
     * existed, where it is the only record of what the client said.
     */
    private String buildDiscoveryContext(Engagement e) {
        return playbookContext.forEngagement(e.getId())
            .map(playbook -> playbookContext.render(playbook, "This client's Purpose Playbook"))
            .orElseGet(() -> buildQuestionnaireContext(e));
    }

    /** The client's raw intake answers — the legacy path, for engagements with no playbook. */
    private String buildQuestionnaireContext(Engagement e) {
        if (e.getQuestionnaireResponseId() == null) return "";
        QuestionnaireResponse response = questionnaireResponseRepo.findById(e.getQuestionnaireResponseId()).orElse(null);
        if (response == null) return "";

        List<QuestionnaireQuestion> questions =
            questionnaireQuestionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(response.getQuestionnaireId());
        Map<String, String> answerByQuestion = questionnaireAnswerRepo
            .findByResponseIdOrderByCreatedAtAsc(response.getId()).stream()
            .collect(Collectors.toMap(QuestionnaireAnswer::getQuestionId, a -> cleanAnswerValue(a.getValueJson())));

        var sb = new StringBuilder("## Original intake questionnaire\n");
        for (QuestionnaireQuestion q : questions) {
            sb.append("Q: ").append(q.getPrompt()).append("\n")
              .append("A: ").append(answerByQuestion.getOrDefault(q.getId(), "(no answer)")).append("\n\n");
        }
        return sb.toString();
    }

    private String cleanAnswerValue(String valueJson) {
        if (valueJson == null) return "(no answer)";
        try {
            JsonNode node = objectMapper.readTree(valueJson);
            if (node.isTextual()) return node.asText();
            if (node.isArray()) {
                List<String> items = new java.util.ArrayList<>();
                for (JsonNode item : node) items.add(item.asText());
                return String.join(", ", items);
            }
            if (node.isNumber()) return node.asText();
            if (node.has("blobPath")) return "(uploaded image)";
            return node.toString();
        } catch (Exception ex) {
            return valueJson;
        }
    }

    private String buildBrief(DirectionOutput dir) {
        var sb = new StringBuilder();

        DirectionBrief brief = dir.brief();
        DirectionBrief.BrandFoundation foundation = brief.foundation();
        DirectionBrief.BrandContext brand = brief.brand();

        sb.append("## Brand foundation (derived from the questionnaire)\n");
        if (brand.coreOffer() != null) sb.append("Core offer: ").append(brand.coreOffer()).append("\n");
        if (foundation != null) {
            if (foundation.differentiator() != null) sb.append("Differentiator: ").append(foundation.differentiator()).append("\n");
            if (foundation.targetAudiencePersona() != null) sb.append("Target audience persona: ").append(foundation.targetAudiencePersona()).append("\n");
            if (foundation.corePositioning() != null) sb.append("Core positioning: ").append(foundation.corePositioning()).append("\n");
            if (foundation.toneSpectrum() != null) sb.append("Tone spectrum: ").append(foundation.toneSpectrum()).append("\n");
        }
        if (brand.personality() != null && !brand.personality().isEmpty()) {
            sb.append("Personality traits: ").append(String.join(", ", brand.personality())).append("\n");
        }
        if (brand.tone() != null) sb.append("Tone: ").append(brand.tone()).append("\n");

        sb.append("\n## Chosen creative direction: ").append(brief.direction()).append("\n");
        if (brief.additionalContext() != null && !brief.additionalContext().isBlank()) {
            sb.append("Strategic rationale: ").append(brief.additionalContext()).append("\n");
        }

        sb.append("\n## Specialist agent output\n");
        var copy = dir.copy();
        var visual = dir.visualIdentity();
        var logo = dir.logo();
        var playbook = dir.playbook();
        var brandBook = dir.brandBook();

        if (copy != null) {
            sb.append("Tagline: ").append(copy.tagline()).append("\n");
            sb.append("Mission: ").append(copy.missionStatement()).append("\n");
            sb.append("Brand story: ").append(copy.brandStory()).append("\n");
            sb.append("Elevator pitch: ").append(copy.elevatorPitch()).append("\n");
            if (copy.toneGuide() != null && copy.toneGuide().principles() != null && !copy.toneGuide().principles().isEmpty()) {
                sb.append("Tone principles: ").append(String.join(", ", copy.toneGuide().principles())).append("\n");
            }
        }
        if (visual != null) {
            if (visual.colorPalette() != null && !visual.colorPalette().isEmpty()) {
                sb.append("Color palette: ").append(visual.colorPalette().stream()
                    .map(c -> c.name() + " (" + c.hex() + ", " + c.role() + ")")
                    .collect(Collectors.joining(", "))).append("\n");
            }
            if (visual.typography() != null) {
                sb.append("Typography: headline ").append(visual.typography().headlineFont())
                    .append(", body ").append(visual.typography().bodyFont()).append("\n");
            }
        }
        if (logo != null) {
            sb.append("Logo concept: ").append(logo.conceptDescription()).append("\n");
        }
        if (playbook != null) {
            sb.append("Strategic summary: ").append(playbook.strategicSummary()).append("\n");
        }
        if (brandBook != null && brandBook.welcomeNote() != null) {
            sb.append("Brand book welcome note: ").append(brandBook.welcomeNote()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public List<ConsultantSubjectSummary> list() {
        return brandRepo.findAll().stream()
            .map(b -> new ConsultantSubjectSummary(b.getId(), b.getName(), b.getUpdatedAt()))
            .toList();
    }

    @Override
    public ConsultantSubjectSummary create(String name, String industry, String audience, String brief) {
        Brand brand = new Brand();
        brand.setId(UUID.randomUUID().toString());
        brand.setName(name);
        brand.setIndustry(industry);
        brand.setAudience(audience);
        brand.setBrief(brief);
        brand = brandRepo.save(brand);
        return new ConsultantSubjectSummary(brand.getId(), brand.getName(), brand.getUpdatedAt());
    }

    @Override
    public void delete(String subjectId) {
        brandRepo.deleteById(subjectId);
    }
}
