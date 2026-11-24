package ai.genesisbrands.standing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the context budget is spent when there is more material than room.
 *
 * <p>This used to be a tail truncation, which was wrong in a way that only showed once a client's
 * brand section grew into a real document: the sections are appended in order of generality, the
 * first one is the only one that can be arbitrarily long, and the last one is the inbox — the
 * client's own approvals and rejections, which a standing run is required to read before repeating
 * itself. A long brand section would silently take the whole budget and the agent would run
 * without the feedback it was meant to act on.
 */
class StandingContextAssemblyTest {

    @Test
    void everythingSurvivesWhenItFits() {
        String out = StandingContextService.assemble(List.of("## Brand\nshort", "## Inbox\nshorter"));

        assertTrue(out.contains("## Brand"));
        assertTrue(out.contains("## Inbox"));
        assertFalse(out.contains("truncated"));
    }

    @Test
    void aSectionTooLongForTheBudgetLosesItsTailAndTheShortOnesSurviveWhole() {
        String brand = "## Brand\n" + "b".repeat(StandingContextService.MAX_CHARS * 2);
        String inbox = "## Inbox\nThe client rejected the last three posts as too salesy.";

        String out = StandingContextService.assemble(List.of(brand, inbox));

        assertTrue(out.contains("(this section was truncated)"));
        // The short section arrives complete, which is the entire point.
        assertTrue(out.contains(inbox));
        assertTrue(out.length() < StandingContextService.MAX_CHARS + 200,
            "the whole context still fits the budget, was " + out.length());
    }

    @Test
    void theSlackFromShortSectionsGoesToTheLongOne() {
        // Three short sections and one long one: an equal split would give the long one a quarter
        // of the budget and waste the rest, so the cap rises to take up what the others leave.
        String out = StandingContextService.assemble(
            List.of("L".repeat(StandingContextService.MAX_CHARS * 2), "a", "b", "c"));

        int longestKept = out.lines().mapToInt(String::length).max().orElse(0);
        assertTrue(longestKept > StandingContextService.MAX_CHARS * 0.9,
            "the long section should have taken nearly the whole budget, got " + longestKept);
    }
}
