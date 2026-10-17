package ai.genesisbrands.agent.playbook;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.RefinementLoop;
import org.springframework.stereotype.Service;

@Service
public class PlaybookRefinementLoop {

    private final PlaybookAgent playbookAgent;
    private final PlaybookEvaluator playbookEvaluator;
    private final AgentProperties agentProperties;

    public PlaybookRefinementLoop(PlaybookAgent playbookAgent, PlaybookEvaluator playbookEvaluator, AgentProperties agentProperties) {
        this.playbookAgent = playbookAgent;
        this.playbookEvaluator = playbookEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementLoop.RefinementResult<PlaybookOutput> run(PlaybookInput input) {
        return RefinementLoop.run("Playbook", input.brief().engagementId(),
            agentProperties.get("playbook").getMaxIterations(),
            () -> playbookAgent.execute(input),
            output -> playbookEvaluator.evaluate(input, output),
            (prev, rev) -> playbookAgent.executeWithRevision(input, prev, rev));
    }
}
