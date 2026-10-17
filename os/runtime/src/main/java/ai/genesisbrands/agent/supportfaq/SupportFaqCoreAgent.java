package ai.genesisbrands.agent.supportfaq;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

/**
 * GenesisOS core utility agent — a RAG-grounded support/FAQ chat. Generic across
 * tenants, so {@link #requiresEngagementContext()} is false. Execution is entirely
 * generic, driven by {@code GenericAgentChatService} via {@code AgentChatController}
 * (Playground/admin) and {@code ClientAgentChatController} (customer-facing, Live).
 */
@Component
public class SupportFaqCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "support-faq";
    }

    @Override
    public String displayName() {
        return "Support/FAQ Agent";
    }

    @Override
    public String description() {
        return "Answers support and FAQ questions, grounded in the knowledge base.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agent-chat/support-faq/chat";
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
