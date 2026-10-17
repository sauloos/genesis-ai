package ai.genesisbrands.agent.summarizer;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

/**
 * GenesisOS core utility agent — condenses a pasted document or call transcript into a
 * structured summary. Generic across tenants (no brand-engagement dependency), so
 * {@link #requiresEngagementContext()} is false: a client can use it with no finished
 * brand journey. Execution is entirely generic, driven by {@code GenericSingleShotAgentService}
 * via {@code AgentGenerationController} (Playground/admin) and
 * {@code GeneratedDocumentController} (customer-facing, Live).
 */
@Component
public class SummarizerCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "summarizer";
    }

    @Override
    public String displayName() {
        return "Document/Call Summarizer";
    }

    @Override
    public String description() {
        return "Condenses a document, transcript, or call recording text into a structured summary.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agent-generation/summarizer/generate";
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
