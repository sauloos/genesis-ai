package ai.genesisbrands.agent.visualidentity;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

@Component
public class VisualIdentityCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "visual-identity";
    }

    @Override
    public String displayName() {
        return "Visual Identity Agent";
    }

    @Override
    public String description() {
        return "Colour palette, typography.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agents/visual-identity/execute";
    }

    @Override
    public boolean supportsABCompare() {
        return true;
    }
}
