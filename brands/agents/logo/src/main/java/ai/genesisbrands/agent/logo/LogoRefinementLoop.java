package ai.genesisbrands.agent.logo;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.RefinementLoop;
import org.springframework.stereotype.Service;

@Service
public class LogoRefinementLoop {

    private final LogoAgent logoAgent;
    private final LogoEvaluator logoEvaluator;
    private final AgentProperties agentProperties;

    public LogoRefinementLoop(LogoAgent logoAgent, LogoEvaluator logoEvaluator, AgentProperties agentProperties) {
        this.logoAgent = logoAgent;
        this.logoEvaluator = logoEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementLoop.RefinementResult<LogoOutput> run(DirectionBrief brief, LogoOutput.Method method) {
        String agentId = switch (method) {
            case DALLE -> "logo-dalle";
            case IDEOGRAM -> "logo-ideogram";
            case SVG_CONCEPT -> "logo-svg";
        };
        return RefinementLoop.run("Logo", brief.engagementId(),
            agentProperties.get(agentId).getMaxIterations(),
            () -> logoAgent.execute(brief, method),
            output -> logoEvaluator.evaluate(brief, output),
            (prev, rev) -> logoAgent.executeWithRevision(brief, prev, rev));
    }
}
