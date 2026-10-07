package ai.genesisbrands.agent.complianceqa;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

/**
 * GenesisOS core utility agent — RAG-grounded policy/compliance Q&A chat. Generic across
 * tenants, so {@link #requiresEngagementContext()} is false. Execution is entirely
 * generic, driven by {@code GenericAgentChatService} via {@code AgentChatController}
 * (Playground/admin) and {@code ClientAgentChatController} (customer-facing, Live).
 */
@Component
public class ComplianceQaCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "compliance-qa";
    }

    @Override
    public String displayName() {
        return "Policy/Compliance Q&A Agent";
    }

    @Override
    public String description() {
        return "Answers policy and compliance questions, grounded in the knowledge base.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agent-chat/compliance-qa/chat";
    }

    @Override
    public boolean chatBased() {
        return true;
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
