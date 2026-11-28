package ai.genesisbrands.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RefinementLoopLoggingTest {

    @Test
    void logsAcceptWithoutRevisionOrSummary() {
        assertThatCode(() -> RefinementLoop.logRound("X", "e1", 1, 3,
            new EvaluationResult(EvaluationResult.Verdict.ACCEPT, null, null))).doesNotThrowAnyException();
    }

    @Test
    void logsReviseWithIssuesAndNullIssueList() {
        var revision = new AgentRevision("fix", List.of("a ".repeat(200), "b"), List.of());
        assertThatCode(() -> RefinementLoop.logRound("X", "e1", 2, 3,
            new EvaluationResult(EvaluationResult.Verdict.REVISE, "too thin", revision))).doesNotThrowAnyException();
        assertThatCode(() -> RefinementLoop.logRound("X", "e1", 2, 3,
            new EvaluationResult(EvaluationResult.Verdict.REVISE, "s", new AgentRevision("f", null, null))))
            .doesNotThrowAnyException();
    }

    @Test
    void loopStillStopsAtCapAndReportsNotAccepted() {
        var result = RefinementLoop.run("X", "e1", 2, () -> "v1",
            out -> new EvaluationResult(EvaluationResult.Verdict.REVISE, "no", new AgentRevision("f", List.of("i"), List.of())),
            (prev, rev) -> prev + "+");
        assertThat(result.accepted()).isFalse();
        assertThat(result.rounds()).hasSize(2);
    }
}
