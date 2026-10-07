package ai.genesisbrands.agent.researchsynthesis;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

/**
 * GenesisOS core utility agent — synthesizes a research question into a grounded
 * answer. Generic across tenants, so {@link #requiresEngagementContext()} is false.
 * Execution is entirely generic, driven by {@code GenericSingleShotAgentService} via
 * {@code AgentGenerationController} (Playground/admin) and
 * {@code GeneratedDocumentController} (customer-facing, Live).
 */
@Component
public class ResearchSynthesisCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "research-synthesis";
    }

    @Override
    public String displayName() {
        return "Research & Synthesis Agent";
    }

    @Override
    public String description() {
        return "Synthesizes a research question or topic into a grounded, structured answer.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agent-generation/research-synthesis/generate";
    }

    @Override
    public boolean hasLiveView() {
        return true;
    }

    @Override
    public boolean hasPlaygroundView() {
        return true;
    }

    @Override
    public boolean requiresEngagementContext() {
        return false;
    }
}
