package ai.genesisbrands.service;

import ai.genesisbrands.model.Brand;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.BrandRepository;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
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
    private final ObjectMapper objectMapper;

    public BrandConsultantSubjectProvider(BrandRepository brandRepo, EngagementRepository engagementRepo, ObjectMapper objectMapper) {
        this.brandRepo = brandRepo;
        this.engagementRepo = engagementRepo;
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

        EngagementResults results;
        try {
            results = objectMapper.readValue(e.getResultsJson(), EngagementResults.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not parse results for engagement: " + engagementId, ex);
        }
        DirectionOutput dir = results.directions().stream()
            .filter(d -> d.direction().equalsIgnoreCase(e.getChosenDirection()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Chosen direction not found in results: " + engagementId));

        var brand = dir.brief().brand();
        return new ConsultantSubject(
            ENGAGEMENT_PREFIX + engagementId,
            brand.name(), brand.industry(), brand.targetAudience(),
            buildBrief(dir)
        );
    }

    private String buildBrief(DirectionOutput dir) {
        var sb = new StringBuilder();
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
