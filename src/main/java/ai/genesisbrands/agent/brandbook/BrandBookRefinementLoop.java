package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The Genesis AI evaluation loop for the Brand Book Assembly Agent: dispatch to the
 * specialist, have Genesis AI (the creative director) evaluate the draft against the
 * brand-book rubric, and either accept it or send it back for revision — up to the
 * agent's configured max-iterations budget.
 */
@Service
public class BrandBookRefinementLoop {

    private static final Logger log = LoggerFactory.getLogger(BrandBookRefinementLoop.class);
    private static final String AGENT_ID = "brand-book";

    private final BrandBookAgent brandBookAgent;
    private final BrandBookEvaluator brandBookEvaluator;
    private final AgentProperties agentProperties;

    public BrandBookRefinementLoop(BrandBookAgent brandBookAgent, BrandBookEvaluator brandBookEvaluator, AgentProperties agentProperties) {
        this.brandBookAgent = brandBookAgent;
        this.brandBookEvaluator = brandBookEvaluator;
        this.agentProperties = agentProperties;
    }

    public RefinementResult run(BrandBookInput input) {
        int maxIterations = agentProperties.get(AGENT_ID).getMaxIterations();

        BrandBookOutput current = brandBookAgent.execute(input);
        List<EvaluationRound> rounds = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            EvaluationResult evaluation = brandBookEvaluator.evaluate(input, current);
            rounds.add(new EvaluationRound(i, evaluation));

            if (evaluation.verdict() == EvaluationResult.Verdict.ACCEPT) {
                return new RefinementResult(current, true, rounds);
            }

            if (i == maxIterations) {
                log.warn("BrandBookRefinementLoop exhausted {} iterations without ACCEPT for engagement {}",
                    maxIterations, input.brief().engagementId());
                break;
            }

            current = brandBookAgent.executeWithRevision(input, current, evaluation.revision());
        }

        return new RefinementResult(current, false, rounds);
    }

    public record EvaluationRound(int iteration, EvaluationResult evaluation) {}

    public record RefinementResult(BrandBookOutput output, boolean accepted, List<EvaluationRound> rounds) {}
}
