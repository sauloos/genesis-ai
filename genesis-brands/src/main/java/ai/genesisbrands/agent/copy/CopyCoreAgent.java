package ai.genesisbrands.agent.copy;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

@Component
public class CopyCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "copy";
    }

    @Override
    public String displayName() {
        return "Copy Agent";
    }

    @Override
    public String description() {
        return "Tagline, mission, brand story, elevator pitch, tone guide.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agents/copy/execute";
    }

    @Override
    public boolean supportsABCompare() {
        return true;
    }
}
