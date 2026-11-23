package ai.genesisbrands.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Skip logic decides which questions a client is asked, so it decides what a brief contains.
 * The cases that matter most here are the failure directions: a broken condition must show
 * its question rather than hide it, and a bad condition must be refused at authoring time.
 */
class QuestionConditionTest {

    @Nested
    class Parsing {

        @Test
        @DisplayName("a blank condition means the question always applies")
        void blankIsAlways() {
            assertThat(QuestionCondition.parse(null)).isEmpty();
            assertThat(QuestionCondition.parse("   ")).isEmpty();
        }

        @Test
        @DisplayName("every supported form parses, including indexed signal keys")
        void parsesEveryForm() {
            assertThat(QuestionCondition.parse("belong.buyerIsUser == 'no'")).contains(
                new QuestionCondition.Clause("belong.buyerIsUser", QuestionCondition.Op.EQUALS, "no"));
            assertThat(QuestionCondition.parse("look.logoIntent != \"keep\"")).contains(
                new QuestionCondition.Clause("look.logoIntent", QuestionCondition.Op.NOT_EQUALS, "keep"));
            assertThat(QuestionCondition.parse("beyond.sdgs contains 'climate-action'")).contains(
                new QuestionCondition.Clause("beyond.sdgs", QuestionCondition.Op.CONTAINS, "climate-action"));
            assertThat(QuestionCondition.parse("look.currentLogo is present")).contains(
                new QuestionCondition.Clause("look.currentLogo", QuestionCondition.Op.PRESENT, null));
            assertThat(QuestionCondition.parse("  look.currentLogo   is   absent  ")).contains(
                new QuestionCondition.Clause("look.currentLogo", QuestionCondition.Op.ABSENT, null));
            assertThat(QuestionCondition.parse("belong.persona[1].story is present")).contains(
                new QuestionCondition.Clause("belong.persona[1].story", QuestionCondition.Op.PRESENT, null));
        }

        @Test
        @DisplayName("a malformed condition is refused, not quietly accepted")
        void rejectsMalformed() {
            for (String expr : List.of(
                "belief.origin",                            // no operator
                "belief.origin == no",                      // unquoted value
                "belief.origin ~ 'no'",                     // unknown operator
                "belief.origin == 'a' && belief.now == 'b'", // no boolean operators, by design
                "== 'no'",                                  // no signal key
                "belief.origin is unknown")) {
                assertThatThrownBy(() -> QuestionCondition.parse(expr))
                    .as(expr)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not a valid condition");
            }
        }
    }

    @Nested
    class Evaluation {

        @Test
        @DisplayName("a blank or unparseable condition shows the question")
        void failsTowardShowing() {
            assertThat(QuestionCondition.applies(null, Map.of())).isTrue();
            assertThat(QuestionCondition.applies("belief.origin ~ 'x'", Map.of())).isTrue();
            assertThat(QuestionCondition.applies("nonsense", Map.of("belief.origin", "x"))).isTrue();
        }

        @Test
        @DisplayName("equality ignores case and surrounding whitespace")
        void equalityIsLenient() {
            var signals = Map.<String, Object>of("belong.buyerIsUser", "  No ");
            assertThat(QuestionCondition.applies("belong.buyerIsUser == 'no'", signals)).isTrue();
            assertThat(QuestionCondition.applies("belong.buyerIsUser == 'yes'", signals)).isFalse();
        }

        @Test
        @DisplayName("a slider's number compares as its text")
        void comparesNumbers() {
            var signals = Map.<String, Object>of("look.slider.era", 4);
            assertThat(QuestionCondition.applies("look.slider.era == '4'", signals)).isTrue();
        }

        @Test
        @DisplayName("== against a multi-select means exactly that one value was picked")
        void equalityAgainstList() {
            assertThat(QuestionCondition.applies("beyond.sdgs == 'climate-action'",
                Map.of("beyond.sdgs", List.of("climate-action")))).isTrue();
            assertThat(QuestionCondition.applies("beyond.sdgs == 'climate-action'",
                Map.of("beyond.sdgs", List.of("climate-action", "good-health")))).isFalse();
        }

        @Test
        @DisplayName("contains is the any-of form and never matches a substring")
        void containsMatchesWholeElements() {
            var signals = Map.<String, Object>of("beyond.sdgs", List.of("climate-action", "good-health"));
            assertThat(QuestionCondition.applies("beyond.sdgs contains 'good-health'", signals)).isTrue();
            assertThat(QuestionCondition.applies("beyond.sdgs contains 'health'", signals)).isFalse();
            assertThat(QuestionCondition.applies("beyond.sdgs contains 'no-poverty'", signals)).isFalse();
        }

        @Test
        @DisplayName("an unanswered, cleared or empty answer counts as absent")
        void emptinessIsAbsence() {
            assertThat(QuestionCondition.applies("look.currentLogo is absent", Map.of())).isTrue();
            assertThat(QuestionCondition.applies("look.currentLogo is present", Map.of())).isFalse();

            for (Object empty : List.of("", "   ", List.of(), Map.of())) {
                var signals = Map.of("look.currentLogo", empty);
                assertThat(QuestionCondition.applies("look.currentLogo is absent", signals))
                    .as(String.valueOf(empty)).isTrue();
            }

            assertThat(QuestionCondition.applies("look.currentLogo is present",
                Map.of("look.currentLogo", Map.of("blobPath", "/api/assets/x.png")))).isTrue();
        }

        @Test
        @DisplayName("a zero answer is present — it was answered")
        void zeroIsPresent() {
            assertThat(QuestionCondition.applies("look.slider.era is present",
                Map.of("look.slider.era", 0))).isTrue();
        }

        @Test
        @DisplayName("!= holds when nothing was answered, so the question still shows")
        void notEqualsOnAbsent() {
            assertThat(QuestionCondition.applies("look.logoIntent != 'keep'", Map.of())).isTrue();
            assertThat(QuestionCondition.applies("look.logoIntent != 'keep'",
                Map.of("look.logoIntent", "keep"))).isFalse();
        }
    }
}
