package ai.genesisbrands.agent.rfpresponse;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

/**
 * GenesisOS core utility agent — drafts RFP/proposal responses. Generic across tenants,
 * so {@link #requiresEngagementContext()} is false. Execution is entirely generic,
 * driven by {@code GenericSingleShotAgentService} via {@code AgentGenerationController}
 * (Playground/admin) and {@code GeneratedDocumentController} (customer-facing, Live).
 */
@Component
public class RfpResponseCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "rfp-response";
    }

    @Override
    public String displayName() {
        return "RFP/Proposal Response Agent";
    }

    @Override
    public String description() {
        return "Drafts a complete response to an RFP, tender, or proposal request.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agent-generation/rfp-response/generate";
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
