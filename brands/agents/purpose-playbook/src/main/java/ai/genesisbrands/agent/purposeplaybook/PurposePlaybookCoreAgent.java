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
 * <p>{@code requiresQuestionnaire()} is true because that is literally its input, and
 * {@code hasLiveView()} is false on purpose: the Live Dashboard surface exists to regenerate an
 * engagement's existing output, and nothing stores a purpose playbook against an engagement
 * until the orchestrator is rewired. Shipping a view against endpoints that do not exist yet
 * would put a clickable card on a client's dashboard that cannot work.
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
