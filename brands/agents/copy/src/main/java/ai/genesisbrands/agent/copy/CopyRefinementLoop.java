package ai.genesisbrands.agent.copy;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.RefinementLoop;
import org.springframework.stereotype.Service;

@Service
public class CopyRefinementLoop {

    private final CopyAgent copyAgent;
    private final CopyEvaluator copyEvaluator;
    private final AgentProperties agentProperties;

    public CopyRefinementLoop(CopyAgent copyAgent, CopyEvaluator copyEvaluator, AgentProperties agentProperties) {
        this.copyAgent = copyAgent;
        this.copyEvaluator = copyEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementLoop.RefinementResult<CopyOutput> run(DirectionBrief brief) {
        return RefinementLoop.run("Copy", brief.engagementId(),
            agentProperties.get("copy").getMaxIterations(),
            () -> copyAgent.execute(brief),
            output -> copyEvaluator.evaluate(brief, output),
            (prev, rev) -> copyAgent.executeWithRevision(brief, prev, rev));
    }
}
