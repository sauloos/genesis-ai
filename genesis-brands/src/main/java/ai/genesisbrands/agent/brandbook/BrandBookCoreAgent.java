package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

@Component
public class BrandBookCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "brand-book";
    }

    @Override
    public String displayName() {
        return "Brand Book Assembly Agent";
    }

    @Override
    public String description() {
        return "Assembles the client-facing brand book PDF.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agents/brand-book/execute";
    }
}
