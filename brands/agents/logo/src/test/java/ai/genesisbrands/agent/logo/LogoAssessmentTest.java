package ai.genesisbrands.agent.logo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogoAssessmentTest {

    private static Map<String, Object> criterion(String key, String weight, Object score) {
        return Map.of("key", key, "label", key, "weight", weight, "score", score, "note", "n");
    }

    @Test
    void overallScoreIsTheWeightedMeanNotTheModelsNumber() {
        Map<String, Object> json = Map.of(
            "score", 99,
            "verdict", "evolve",
            "criteria", List.of(
                criterion("meaning", "critical", 4),      // 3 x 4 = 12
                criterion("distinctiveness", "critical", 3), // 3 x 3 = 9
                criterion("craft", "high", 8),             // 2 x 8 = 16
                criterion("equity", "medium", 7)),         // 1 x 7 = 7
            "keep", List.of("the wordmark's weight"),
            "confidence", "medium — the file is small");
        LogoAssessment a = LogoAssessment.from(json);

        // (12 + 9 + 16 + 7) / 9 weights = 4.89 -> 49
        assertThat(a.score()).isEqualTo(49);
        assertThat(a.verdict()).isEqualTo(LogoAssessment.EVOLVE);
        assertThat(a.confidence()).isEqualTo("MEDIUM");
        assertThat(a.change()).isEmpty();
        assertThat(a.renderForDesigner()).contains("49/100").contains("Carry over: the wordmark's weight");
    }

    @Test
    void scoresAreClampedToTheScale() {
        LogoAssessment a = LogoAssessment.from(Map.of("verdict", "KEEP",
            "criteria", List.of(criterion("craft", "high", 14.6), criterion("fit", "high", -2))));
        assertThat(a.criteria()).extracting(LogoAssessment.Criterion::score).containsExactly(10, 0);
        assertThat(a.score()).isEqualTo(50);
    }

    @Test
    void anAssessmentWithoutCriteriaOrVerdictIsRefused() {
        assertThatThrownBy(() -> LogoAssessment.from(Map.of("verdict", "KEEP", "criteria", List.of())))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LogoAssessment.from(Map.of("verdict", "MAYBE",
            "criteria", List.of(criterion("craft", "high", 5)))))
            .isInstanceOf(IllegalStateException.class);
    }
}
