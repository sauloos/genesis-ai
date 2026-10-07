package ai.genesisbrands.agent.writer;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

/**
 * GenesisOS core utility agent — drafts emails and short documents. Generic across
 * tenants, so {@link #requiresEngagementContext()} is false. Execution is entirely
 * generic, driven by {@code GenericSingleShotAgentService} via
 * {@code AgentGenerationController} (Playground/admin) and
 * {@code GeneratedDocumentController} (customer-facing, Live).
 */
@Component
public class WriterCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "writer";
    }

    @Override
    public String displayName() {
        return "Writer Agent";
    }

    @Override
    public String description() {
        return "Drafts emails, memos, and short documents from a brief description of what's needed.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agent-generation/writer/generate";
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
