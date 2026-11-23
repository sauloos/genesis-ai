package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.lookkit.LookKit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digest is where a flat answer map becomes something a model can reason over, so every
 * assertion here is about a shape the prompt depends on: repeats grouped into blocks, look slugs
 * resolved into design terms, and the three kinds of absence kept apart.
 */
class SignalDigestTest {

    private final SignalDigest digest = new SignalDigest(new LookKit(), new ObjectMapper());

    // ── repeat instances ────────────────────────────────────────────────────────────────────

    @Test
    void readsTheRepeatInstanceAKeyBelongsTo() {
        assertThat(SignalDigest.instanceOf("belong.persona[0].story")).isEqualTo("belong.persona[0]");
        assertThat(SignalDigest.instanceOf("best.competitor[2].url")).isEqualTo("best.competitor[2]");
        assertThat(SignalDigest.instanceOf("setup.sector")).isEmpty();
    }

    @Test
    void leavesAKeyThatEndsInAnIndexUngrouped() {
        // beyond.visionBoardDetail[2] is a value in its own right, not one field of a block.
        assertThat(SignalDigest.instanceOf("beyond.visionBoardDetail[2]")).isEmpty();
    }

    @Test
    void groupsTheFlattenedRepeatsIntoBlocksUnderTheirPillar() {
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("belong.persona[0].story", "The ops lead who inherited the problem");
        signals.put("belong.persona[1].story", "The founder doing it themselves at midnight");
        signals.put("belong.persona[0].decisionDriver", "Not being blamed for the handover");
        signals.put("setup.sector", "Logistics software");

        String rendered = digest.of(input(signals), null).rendered();

        assertThat(rendered).contains("### belong").contains("### setup");
        // Both of persona 0's fields sit under one heading, away from persona 1's.
        assertThat(rendered.indexOf("belong.persona[0].decisionDriver"))
            .isLessThan(rendered.indexOf("belong.persona[1]\n"));
        assertThat(rendered).contains("belong.persona[1]\n- belong.persona[1].story:");
    }

    // ── describing values ───────────────────────────────────────────────────────────────────

    @Test
    void describesASliderAsAPositionOutOfFive() {
        assertThat(digest.describe("look.slider.volume", 4)).isEqualTo("4 of 5");
        assertThat(digest.describe("setup.headcount", 4)).isEqualTo("4");
    }

    @Test
    void describesAnUploadByTheFactThatOneExists() {
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("name", "current-logo.svg");
        upload.put("contentType", "image/svg+xml");

        assertThat(digest.describe("look.currentLogo", upload))
            .isEqualTo("uploaded file \"current-logo.svg\" (image/svg+xml)");
    }

    @Test
    void describesAnUploadWithNoContentTypeWithoutGuessingOne() {
        assertThat(digest.describe("look.existingAssets", Map.of("name", "deck.pdf")))
            .isEqualTo("uploaded file \"deck.pdf\" (unknown type)");
    }

    @Test
    void joinsAMultipleChoiceAnswerAndMarksAnEmptyOne() {
        assertThat(digest.describe("look.placements", List.of("website", "van livery")))
            .isEqualTo("website; van livery");
        assertThat(digest.describe("look.placements", List.of())).isEqualTo("(nothing selected)");
        assertThat(digest.describe("look.placements", null)).isEqualTo("(no answer)");
    }

    @Test
    void resolvesALookSlugIntoTheDesignTermBehindIt() {
        assertThat(digest.describe("look.palette.like", "warm-earth"))
            .contains("Warm earth")
            .contains("Clay, sand and rust")
            .contains("primary #B55B33")
            .doesNotContain("warm-earth");

        assertThat(digest.describe("look.pairs.mark", "mark-organic")).contains("organic");
        assertThat(digest.describe("look.typeSpecimen", "editorial-serif"))
            .contains("editorial serif");
    }

    @Test
    void leavesTheFreeTextLookAnswersAlone() {
        assertThat(digest.describe("look.mustKeep", "the van blue stays"))
            .isEqualTo("the van blue stays");
        assertThat(digest.describe("look.palette.forbidden", "anything near NHS blue"))
            .isEqualTo("anything near NHS blue");
    }

    // ── the three absences ──────────────────────────────────────────────────────────────────

    @Test
    void separatesATerseAnswerFromASkippedOne() {
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("setup.oneLiner", "We do logistics");
        signals.put("belief.why", "Because the handover between a warehouse and a driver is still "
            + "run on paper, and everybody downstream pays for it.");
        signals.put("look.slider.volume", 4);

        SignalDigest.Digest result = digest.of(
            new PurposePlaybookInput("eng-1", signals,
                List.of("setup.oneLiner", "belief.why", "look.slider.volume", "beyond.legacy"),
                null, null),
            null);

        assertThat(result.terse()).containsExactly("setup.oneLiner");
        assertThat(result.skipped()).containsExactly("beyond.legacy");
        assertThat(result.notCollected()).isEmpty();
        assertThat(result.hasGaps()).isTrue();
    }

    @Test
    void reportsNoGapsOnACompletedQuestionnaire() {
        Map<String, Object> signals = Map.of("setup.oneLiner",
            "We shorten the handover between a warehouse and a driver.");

        SignalDigest.Digest result = digest.of(
            new PurposePlaybookInput("eng-1", signals, List.of("setup.oneLiner"), null, null), null);

        assertThat(result.hasGaps()).isFalse();
    }

    @Test
    void readsWhatTheStructureModuleNeedsButTheQuestionnaireNeverAsked() {
        String module = """
            sections:
              - key: situation
                from: [setup.stage, setup.sector]
              - key: legacy
                from: [beyond.legacy]
            """;

        List<String> absent = digest.notCollected(module,
            Set.of("setup.stage"), List.of("setup.stage", "setup.sector"));

        // setup.sector was asked and skipped — that is the client's gap, not the instrument's.
        assertThat(absent).containsExactly("beyond.legacy");
    }

    @Test
    void countsAWildcardAsCollectedWhenAnythingUnderItWasAsked() {
        String module = "from: [belief.cause.*, belong.persona.decisionDriver, beyond.sdg.*]\n";

        List<String> absent = digest.notCollected(module,
            Set.of("belief.cause.unfair", "belong.persona[1].decisionDriver"), List.of());

        assertThat(absent).containsExactly("beyond.sdg.*");
    }

    @Test
    void matchesAReferenceToAParentOfWhatWasCollected() {
        String module = "from: [belong.persona, best.competitor]\n";

        assertThat(digest.notCollected(module, Set.of("belong.persona[0].story"), List.of()))
            .containsExactly("best.competitor");
    }

    @Test
    void findsNothingAbsentWithoutAModuleToReadIt() {
        assertThat(digest.notCollected(null, Set.of(), List.of())).isEmpty();
        assertThat(digest.notCollected("  ", Set.of(), List.of())).isEmpty();
    }

    private static PurposePlaybookInput input(Map<String, Object> signals) {
        return new PurposePlaybookInput("eng-1", signals, List.copyOf(signals.keySet()), null, null);
    }
}
