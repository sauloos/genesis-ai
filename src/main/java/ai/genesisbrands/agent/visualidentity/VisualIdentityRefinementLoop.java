package ai.genesisbrands.agent.visualidentity;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The Genesis AI evaluation loop for the Visual Identity Agent: dispatch to the
 * specialist, have Genesis AI (the creative director) evaluate the draft against the
 * visual identity rubric, and either accept it or send it back for revision — up to
 * the agent's configured max-iterations budget.
 */
@Service
public class VisualIdentityRefinementLoop {

    private static final Logger log = LoggerFactory.getLogger(VisualIdentityRefinementLoop.class);
    private static final String AGENT_ID = "visual-identity";

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

    public RefinementResult run(DirectionBrief brief) {
        int maxIterations = agentProperties.get(AGENT_ID).getMaxIterations();

        VisualIdentityOutput current = visualIdentityAgent.execute(brief);
        List<EvaluationRound> rounds = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            EvaluationResult evaluation = visualIdentityEvaluator.evaluate(brief, current);
            rounds.add(new EvaluationRound(i, evaluation));

            if (evaluation.verdict() == EvaluationResult.Verdict.ACCEPT) {
                return new RefinementResult(current, true, rounds);
            }

            if (i == maxIterations) {
                log.warn("VisualIdentityRefinementLoop exhausted {} iterations without ACCEPT for engagement {}",
                    maxIterations, brief.engagementId());
                break;
            }

            current = visualIdentityAgent.executeWithRevision(brief, current, evaluation.revision());
        }

        return new RefinementResult(current, false, rounds);
    }

    public record EvaluationRound(int iteration, EvaluationResult evaluation) {}

    public record RefinementResult(VisualIdentityOutput output, boolean accepted, List<EvaluationRound> rounds) {}
}
