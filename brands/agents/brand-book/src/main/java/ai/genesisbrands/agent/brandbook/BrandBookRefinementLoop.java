package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.RefinementLoop;
import org.springframework.stereotype.Service;

@Service
public class BrandBookRefinementLoop {

    private final BrandBookAgent brandBookAgent;
    private final BrandBookEvaluator brandBookEvaluator;
    private final AgentProperties agentProperties;

    public BrandBookRefinementLoop(BrandBookAgent brandBookAgent, BrandBookEvaluator brandBookEvaluator, AgentProperties agentProperties) {
        this.brandBookAgent = brandBookAgent;
        this.brandBookEvaluator = brandBookEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementLoop.RefinementResult<BrandBookOutput> run(BrandBookInput input) {
        return RefinementLoop.run("BrandBook", input.brief().engagementId(),
            agentProperties.get("brand-book").getMaxIterations(),
            () -> brandBookAgent.execute(input),
            output -> brandBookEvaluator.evaluate(input, output),
            (prev, rev) -> brandBookAgent.executeWithRevision(input, prev, rev));
    }
}
