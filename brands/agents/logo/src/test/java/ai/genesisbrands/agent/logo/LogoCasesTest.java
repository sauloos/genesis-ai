package ai.genesisbrands.agent.logo;

import ai.genesisbrands.platform.knowledge.KnowledgeCases;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogoCasesTest {

    @Test
    void rendersNothingWithoutApprovedCases() {
        assertThat(LogoCases.render(List.of())).isEmpty();
        assertThat(LogoCases.render(null)).isEmpty();
    }

    @Test
    void rendersEachCaseWithItsVerdictAndLesson() {
        String block = LogoCases.render(List.of(
            new KnowledgeCases.Case("BAD", "Valve mark", "coffee", List.of("jargon"),
                "Draws the degassing valve.", "Never build on a packaging part.")));
        assertThat(block).contains("## Cases from the agency's library")
            .contains("- [BAD] Valve mark (coffee): Draws the degassing valve. Lesson: Never build on a packaging part.");
    }
}
