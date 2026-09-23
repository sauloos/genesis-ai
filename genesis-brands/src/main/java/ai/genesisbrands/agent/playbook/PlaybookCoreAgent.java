package ai.genesisbrands.agent.playbook;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

@Component
public class PlaybookCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "playbook";
    }

    @Override
    public String displayName() {
        return "Playbook Assembly Agent";
    }

    @Override
    public String description() {
        return "Assembles the internal brand playbook document.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agents/playbook/execute";
    }
}
