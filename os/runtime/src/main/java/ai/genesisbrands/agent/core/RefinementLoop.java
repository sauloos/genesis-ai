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
            logRound(agentName, engagementId, i, maxIterations, evaluation);

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

    /**
     * One line per evaluator round, so a loop that never accepts can be explained afterwards
     * (which verdict, what the evaluator said, which issues) instead of only costing money.
     */
    static void logRound(String agentName, String engagementId, int round, int maxIterations,
                         EvaluationResult evaluation) {
        AgentRevision rev = evaluation.revision();
        int issues = rev == null || rev.specificIssues() == null ? 0 : rev.specificIssues().size();
        String firstIssues = issues == 0 ? "" : rev.specificIssues().stream().limit(3)
            .map(s -> clip(s, 160)).collect(java.util.stream.Collectors.joining(" | "));
        log.info("{} evaluation round {}/{} for engagement {}: {} — {} [{} issue(s): {}]",
            agentName, round, maxIterations, engagementId, evaluation.verdict(),
            clip(evaluation.summary(), 300), issues, firstIssues);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    public record EvaluationRound(int iteration, EvaluationResult evaluation) {}

    public record RefinementResult<T>(T output, boolean accepted, List<EvaluationRound> rounds) {}
}
