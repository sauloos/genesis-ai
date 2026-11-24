package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

/**
 * Registry entry for the Purpose Playbook agent — the one document per engagement that the
 * three creative directions and every specialist agent are briefed from.
 *
 * <p>Not to be confused with the Playbook Assembly agent ({@code playbook}), which writes one
 * brand playbook per direction after copy, visual identity and logo exist. This one runs
 * before any direction exists, from the discovery questionnaire alone.
 *
 * <p>{@code requiresQuestionnaire()} is true because that is literally its input.
 *
 * <p>{@code hasLiveView()} is false, and now for a different reason than when it was written: the
 * orchestrator does store a playbook against the engagement. What a SLOT agent's Live Dashboard
 * view offers is a regenerate command, and regenerating this one is not a regeneration — every
 * direction and all five specialists are briefed from it, so re-deriving it invalidates the entire
 * engagement rather than one asset. The document is also derived from discovery answers that have
 * not changed, so a second derivation is churn, not a correction; changing what the playbook says
 * means answering discovery differently, which is a new engagement.
 *
 * <p>Showing a client their own playbook read-only is a separate question, and a product one —
 * it would be surfaced through {@code ClientWorkspaceProvider} like their other documents, not as
 * an agent card. It is not implemented.
 */
@Component
public class PurposePlaybookCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "purpose-playbook";
    }

    @Override
    public String displayName() {
        return "Purpose Playbook Agent";
    }

    @Override
    public String description() {
        return "Derives the per-engagement Purpose Playbook — discovery plus strategy — from the "
            + "client's discovery questionnaire. Every direction and specialist is briefed from it.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agents/purpose-playbook/execute";
    }

    @Override
    public String icon() {
        return "🧭";
    }

    @Override
    public boolean requiresQuestionnaire() {
        return true;
    }

    @Override
    public boolean hasPlaygroundView() {
        return true;
    }

    @Override
    public boolean hasLiveView() {
        return false;
    }
}
