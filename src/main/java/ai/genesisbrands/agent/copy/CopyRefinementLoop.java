package ai.genesisbrands.agent.copy;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The Genesis AI evaluation loop for the Copy Agent: dispatch to the specialist,
 * have Genesis AI (the creative director) evaluate the draft against the copy
 * rubric, and either accept it or send it back for revision — up to the agent's
 * configured max-iterations budget.
 */
@Service
public class CopyRefinementLoop {

    private static final Logger log = LoggerFactory.getLogger(CopyRefinementLoop.class);
    private static final String AGENT_ID = "copy";

    private final CopyAgent copyAgent;
    private final CopyEvaluator copyEvaluator;
    private final AgentProperties agentProperties;

    public CopyRefinementLoop(CopyAgent copyAgent, CopyEvaluator copyEvaluator, AgentProperties agentProperties) {
        this.copyAgent = copyAgent;
        this.copyEvaluator = copyEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementResult run(DirectionBrief brief) {
        int maxIterations = agentProperties.get(AGENT_ID).getMaxIterations();

        CopyOutput current = copyAgent.execute(brief);
        List<EvaluationRound> rounds = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            EvaluationResult evaluation = copyEvaluator.evaluate(brief, current);
            rounds.add(new EvaluationRound(i, evaluation));

            if (evaluation.verdict() == EvaluationResult.Verdict.ACCEPT) {
                return new RefinementResult(current, true, rounds);
            }

            if (i == maxIterations) {
                log.warn("CopyRefinementLoop exhausted {} iterations without ACCEPT for engagement {}",
                    maxIterations, brief.engagementId());
                break;
            }

            current = copyAgent.executeWithRevision(brief, current, evaluation.revision());
        }

        return new RefinementResult(current, false, rounds);
    }

    public record EvaluationRound(int iteration, EvaluationResult evaluation) {}

    public record RefinementResult(CopyOutput output, boolean accepted, List<EvaluationRound> rounds) {}
}
