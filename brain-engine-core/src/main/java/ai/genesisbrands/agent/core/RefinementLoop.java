package ai.genesisbrands.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Generic evaluation loop shared by all specialist agents.
 * Tenant-specific agent and evaluator logic plugs in via lambdas —
 * the loop structure itself is a platform concern.
 */
public class RefinementLoop {

    private static final Logger log = LoggerFactory.getLogger(RefinementLoop.class);

    private RefinementLoop() {}

    public static <T> RefinementResult<T> run(
            String agentName,
            String engagementId,
            int maxIterations,
            Supplier<T> initialExecution,
            Function<T, EvaluationResult> evaluate,
            BiFunction<T, AgentRevision, T> revise) {

        T current = initialExecution.get();
        List<EvaluationRound> rounds = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            EvaluationResult evaluation = evaluate.apply(current);
            rounds.add(new EvaluationRound(i, evaluation));

            if (evaluation.verdict() == EvaluationResult.Verdict.ACCEPT) {
                return new RefinementResult<>(current, true, rounds);
            }

            if (i == maxIterations) {
                log.warn("{} loop exhausted {} iterations without ACCEPT for engagement {}",
                    agentName, maxIterations, engagementId);
                break;
            }

            current = revise.apply(current, evaluation.revision());
        }

        return new RefinementResult<>(current, false, rounds);
    }

    public record EvaluationRound(int iteration, EvaluationResult evaluation) {}

    public record RefinementResult<T>(T output, boolean accepted, List<EvaluationRound> rounds) {}
}
