package ai.genesisbrands.agent.logo;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

@Component
public class LogoCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "logo";
    }

    @Override
    public String displayName() {
        return "Logo Agent";
    }

    @Override
    public String description() {
        return "Logo mark concept.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agents/logo/execute";
    }
}
