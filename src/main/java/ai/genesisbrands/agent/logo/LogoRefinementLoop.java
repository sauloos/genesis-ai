package ai.genesisbrands.agent.logo;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The Genesis AI evaluation loop for the Logo Agent: dispatch to the specialist (either
 * generation method), have Genesis AI evaluate the draft against the logo rubric, and
 * either accept it or send it back for revision — up to the agent's configured
 * max-iterations budget. Each revision round for DALLE regenerates a fresh image.
 */
@Service
public class LogoRefinementLoop {

    private static final Logger log = LoggerFactory.getLogger(LogoRefinementLoop.class);
    private static final String AGENT_ID_DALLE = "logo-dalle";
    private static final String AGENT_ID_SVG = "logo-svg";

    private final LogoAgent logoAgent;
    private final LogoEvaluator logoEvaluator;
    private final AgentProperties agentProperties;

    public LogoRefinementLoop(LogoAgent logoAgent, LogoEvaluator logoEvaluator, AgentProperties agentProperties) {
        this.logoAgent = logoAgent;
        this.logoEvaluator = logoEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementResult run(DirectionBrief brief, LogoOutput.Method method) {
        String agentId = method == LogoOutput.Method.DALLE ? AGENT_ID_DALLE : AGENT_ID_SVG;
        int maxIterations = agentProperties.get(agentId).getMaxIterations();

        LogoOutput current = logoAgent.execute(brief, method);
        List<EvaluationRound> rounds = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            EvaluationResult evaluation = logoEvaluator.evaluate(brief, current);
            rounds.add(new EvaluationRound(i, evaluation));

            if (evaluation.verdict() == EvaluationResult.Verdict.ACCEPT) {
                return new RefinementResult(current, true, rounds);
            }

            if (i == maxIterations) {
                log.warn("LogoRefinementLoop exhausted {} iterations without ACCEPT for engagement {}",
                    maxIterations, brief.engagementId());
                break;
            }

            current = logoAgent.executeWithRevision(brief, current, evaluation.revision());
        }

        return new RefinementResult(current, false, rounds);
    }

    public record EvaluationRound(int iteration, EvaluationResult evaluation) {}

    public record RefinementResult(LogoOutput output, boolean accepted, List<EvaluationRound> rounds) {}
}
