package ai.genesisbrands.service;

import ai.genesisbrands.model.AgentRegenerationJob;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Genesis Brands' {@link ConsultantToolProvider}: lets the customer-facing Consultant chat
 * trigger regeneration of its own client's SLOT agent output, through the exact same
 * draft/approve/reject gate as the Live Dashboard ({@link AgentRegenerationService}) — never
 * an immediate mutation. Only ever attached to a chat when the admin has turned Consultant's
 * {@code toolsEnabled} flag on and the request's Source is CUSTOMER (see
 * {@code ConsultantService.buildToolOptions} in brain-engine-core) — the admin/Playground
 * "pure consultant" sandbox never gets these tools regardless of the toggle.
 * <p>
 * {@code subjectId} here is always {@code "engagement:" + engagementId}, the synthetic id
 * {@link BrandConsultantSubjectProvider} resolves and {@code ClientConsultantController} is
 * the only ever caller to construct — so the engagement it names is already known to belong
 * to the authenticated client by the time a tool executes.
 */
@Component
public class ConsultantRegenerationToolProvider implements ConsultantToolProvider {

    private static final String ENGAGEMENT_PREFIX = "engagement:";

    private record ToolSpec(String agentId, String toolName, String description) {}

    private static final List<ToolSpec> TOOL_SPECS = List.of(
        new ToolSpec("copy", "regenerate_copy",
            "Regenerate this client's tagline, mission statement, brand story, elevator pitch and tone guide. Produces a draft the client must review and approve from their dashboard before anything changes."),
        new ToolSpec("visual-identity", "regenerate_visual_identity",
            "Regenerate this client's colour palette and typography. Produces a draft the client must review and approve from their dashboard before anything changes."),
        new ToolSpec("logo", "regenerate_logo",
            "Regenerate this client's logo concept. Produces a draft the client must review and approve from their dashboard before anything changes."),
        new ToolSpec("playbook", "regenerate_playbook",
            "Regenerate this client's brand playbook. Produces a draft the client must review and approve from their dashboard before anything changes."),
        new ToolSpec("brand-book", "regenerate_brand_book",
            "Regenerate this client's brand book. Produces a draft the client must review and approve from their dashboard before anything changes.")
    );

    private final AgentRegenerationService regenerationService;
    private final EngagementRepository engagementRepo;

    public ConsultantRegenerationToolProvider(AgentRegenerationService regenerationService,
                                               EngagementRepository engagementRepo) {
        this.regenerationService = regenerationService;
        this.engagementRepo = engagementRepo;
    }

    @Override
    public List<ToolCallback> toolsFor(String subjectId) {
        if (subjectId == null || !subjectId.startsWith(ENGAGEMENT_PREFIX)) {
            return List.of();
        }
        String engagementId = subjectId.substring(ENGAGEMENT_PREFIX.length());

        return TOOL_SPECS.stream()
            .<ToolCallback>map(spec -> FunctionToolCallback
                .builder(spec.toolName(), (RegenerateInput input) -> regenerate(engagementId, spec.agentId(), input))
                .description(spec.description())
                .inputType(RegenerateInput.class)
                .build())
            .toList();
    }

    private String regenerate(String engagementId, String agentId, RegenerateInput input) {
        Engagement engagement = engagementRepo.findById(engagementId)
            .orElseThrow(() -> new NoSuchElementException("Engagement not found: " + engagementId));
        String feedback = input != null ? input.feedback() : null;

        AgentRegenerationJob job = regenerationService.requestRegeneration(
            engagementId, engagement.getChosenDirection(), agentId, feedback, engagement.getClientUserId());

        return "Started regenerating. Job id: " + job.getId() + ", status: " + job.getStatus()
            + ". This only produces a draft — nothing changes until the client reviews and approves it from their dashboard.";
    }

    /** {@code feedback} is optional — omitted or blank just means "regenerate with no specific
     *  steering", matching the Live Dashboard's own optional feedback field. */
    public record RegenerateInput(String feedback) {}
}
