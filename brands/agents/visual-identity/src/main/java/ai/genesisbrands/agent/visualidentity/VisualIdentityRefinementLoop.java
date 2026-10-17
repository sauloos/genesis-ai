package ai.genesisbrands.agent.visualidentity;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.RefinementLoop;
import org.springframework.stereotype.Service;

@Service
public class VisualIdentityRefinementLoop {

    private final VisualIdentityAgent visualIdentityAgent;
    private final VisualIdentityEvaluator visualIdentityEvaluator;
    private final AgentProperties agentProperties;

    public VisualIdentityRefinementLoop(VisualIdentityAgent visualIdentityAgent,
                                         VisualIdentityEvaluator visualIdentityEvaluator,
                                         AgentProperties agentProperties) {
        this.visualIdentityAgent = visualIdentityAgent;
        this.visualIdentityEvaluator = visualIdentityEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementLoop.RefinementResult<VisualIdentityOutput> run(DirectionBrief brief) {
        return RefinementLoop.run("VisualIdentity", brief.engagementId(),
            agentProperties.get("visual-identity").getMaxIterations(),
            () -> visualIdentityAgent.execute(brief),
            output -> visualIdentityEvaluator.evaluate(brief, output),
            (prev, rev) -> visualIdentityAgent.executeWithRevision(brief, prev, rev));
    }
}
