package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.RefinementLoop;
import org.springframework.stereotype.Service;

@Service
public class PurposePlaybookRefinementLoop {

    private final PurposePlaybookAgent agent;
    private final PurposePlaybookEvaluator evaluator;
    private final AgentProperties agentProperties;

    public PurposePlaybookRefinementLoop(PurposePlaybookAgent agent,
                                         PurposePlaybookEvaluator evaluator,
                                         AgentProperties agentProperties) {
        this.agent = agent;
        this.evaluator = evaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementLoop.RefinementResult<PurposePlaybookOutput> run(PurposePlaybookInput input) {
        return RefinementLoop.run("PurposePlaybook", input.engagementId(),
            agentProperties.get(PurposePlaybookAgent.AGENT_ID).getMaxIterations(),
            () -> agent.execute(input),
            output -> evaluator.evaluate(input, output),
            (previous, revision) -> agent.executeWithRevision(input, previous, revision));
    }
}
