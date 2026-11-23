package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.service.Layer1Service;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mechanical half of the rubric, tested against the real structure module rather than a
 * fixture — the point of reading the required sections out of Layer 1 is that a knowledge-base
 * edit changes the check, so a fixture would test the parser against itself and miss exactly the
 * drift that matters.
 */
class PurposePlaybookGuardTest {

    private static final String STRUCTURE = module(PurposePlaybookAgent.STRUCTURE_KEY);

    private final PurposePlaybookGuard guard = new PurposePlaybookGuard(new Layer1Service() {
        @Override
        public String getModule(String key) {
            if (PurposePlaybookAgent.STRUCTURE_KEY.equals(key)) {
                return STRUCTURE;
            }
            throw new IllegalStateException("No Layer 1 module found for: " + key);
        }
    });

    // ── required section keys ────────────────────────────────────────────────────────────────

    @Test
    void readsTheRequiredSubsectionsOutOfTheRealModule() {
        List<String> keys = PurposePlaybookGuard.requiredSectionKeys(STRUCTURE);

        assertThat(keys).contains("cover", "situation",
            "belief.mission", "belief.vision", "belief.values", "belief.neverList",
            "belong.personas", "belong.painPoints", "best.valueProposition", "best.competitors",
            "beyond.sdgs", "beyond.visionBoard",
            "strategy.positioningSummary", "strategy.lookAndFeel", "strategy.directionGuidance");
    }

    @Test
    void doesNotRequireAPillarContainerAsASectionOfItsOwn() {
        // belief/belong/best/beyond/strategy are `required: true` and hold only subsections. The
        // document has a belief.mission body, never a belief one — requiring the container would
        // have failed every generation ever produced.
        assertThat(PurposePlaybookGuard.requiredSectionKeys(STRUCTURE))
            .doesNotContain("belief", "belong", "best", "beyond", "strategy");
    }

    @Test
    void leavesOutTheOptionalSubsectionsAndTheLiftedConfidenceMap() {
        assertThat(PurposePlaybookGuard.requiredSectionKeys(STRUCTURE))
            .doesNotContain("belong.buyingPersona", "belong.generations", "beyond.giveBack",
                "strategy.scorecard", "strategy.flywheel", "confidence");
    }

    @Test
    void failsOpenOnAModuleShapeItDoesNotRecognise() {
        assertThat(PurposePlaybookGuard.requiredSectionKeys(null)).isEmpty();
        assertThat(PurposePlaybookGuard.requiredSectionKeys("")).isEmpty();
        assertThat(PurposePlaybookGuard.requiredSectionKeys("module: something-else\n")).isEmpty();
    }

    @Test
    void treatsANestedKeyAsProofTheSectionAboveItIsAContainer() {
        // The same shape without the `subsections:` marker — a module edit must not be able to
        // reintroduce the container bug by dropping one line.
        String module = """
            sections:
              - key: belief
                required: true
                  - key: mission
                    required: true
              - key: situation
                required: true
            """;
        assertThat(PurposePlaybookGuard.requiredSectionKeys(module))
            .containsExactly("belief.mission", "situation");
    }

    // ── per-document checks ──────────────────────────────────────────────────────────────────

    @Test
    void passesACompleteDocument() {
        assertThat(guard.check(input(Map.of()), complete())).isEmpty();
    }

    @Test
    void reportsAMissingRequiredSection() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.remove("belief.mission");

        assertThat(guard.check(input(Map.of()), with(output, sections)))
            .anySatisfy(v -> assertThat(v).contains("belief.mission").contains("is missing"));
    }

    @Test
    void reportsASectionThatNamesNoSources() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("situation", new PurposePlaybookOutput.Section(
            "situation", "Where you are", "You are two years in.", List.of(), "medium"));

        assertThat(guard.check(input(Map.of()), with(output, sections)))
            .anySatisfy(v -> assertThat(v).contains("situation").contains("names no sources"));
    }

    @Test
    void reportsAnEmptyBody() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("situation", new PurposePlaybookOutput.Section(
            "situation", "Where you are", "   ", List.of("setup.stage"), "medium"));

        assertThat(guard.check(input(Map.of()), with(output, sections)))
            .anySatisfy(v -> assertThat(v).contains("situation").contains("empty body"));
    }

    @Test
    void reportsTheWordAvatar() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("belong.personas", new PurposePlaybookOutput.Section(
            "belong.personas", "Who you are for",
            "Your first avatar is the operations lead who inherited the problem.",
            List.of("belong.persona[0].story"), "high"));

        assertThat(guard.check(input(Map.of()), with(output, sections)))
            .anySatisfy(v -> assertThat(v).contains("avatar").contains("persona"));
    }

    @Test
    void reportsANeverListWordThatHasBeenDropped() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("belief.neverList", new PurposePlaybookOutput.Section(
            "belief.neverList", "What you will never be",
            "You will never be called cheap.", List.of("belief.neverWords"), "high"));

        List<String> violations = guard.check(
            input(Map.of("belief.neverWords", List.of("cheap", "corporate", "disposable"))),
            with(output, sections));

        assertThat(violations).anySatisfy(v -> assertThat(v)
            .contains("never-list has been edited")
            .contains("corporate")
            .contains("disposable"));
    }

    @Test
    void acceptsANeverListCarriedWholeHoweverItIsWorded() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("belief.neverList", new PurposePlaybookOutput.Section(
            "belief.neverList", "What you will never be",
            "Never Cheap, never corporate, never disposable — in that order.",
            List.of("belief.neverWords"), "high"));

        assertThat(guard.check(
            input(Map.of("belief.neverWords", List.of("cheap", "corporate", "disposable"))),
            with(output, sections)))
            .isEmpty();
    }

    @Test
    void reportsTheCauseScaffoldAsALabelledSectionWithoutFlaggingOrdinaryProse() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("belief.mission", new PurposePlaybookOutput.Section(
            "belief.mission", "Your CAUSE", "You exist to shorten the handover.",
            List.of("belief.why"), "high"));
        sections.put("situation", new PurposePlaybookOutput.Section(
            "situation", "Where you are, and because of what",
            "You are two years in.", List.of("setup.stage"), "medium"));

        List<String> violations = guard.check(input(Map.of()), with(output, sections));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("belief.mission").contains("CAUSE");
    }

    @Test
    void reportsASectionTitledLikeABrandPlaybookField() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("strategy.positioningSummary", new PurposePlaybookOutput.Section(
            "strategy.positioningSummary", "Your positioning statement",
            "You sit between the generalists and the in-house team.",
            List.of("best.usp"), "medium"));

        assertThat(guard.check(input(Map.of()), with(output, sections)))
            .anySatisfy(v -> assertThat(v).contains("positioning statement"));
    }

    @Test
    void reportsAColourValueTheClientNeverNamed() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("strategy.lookAndFeel", new PurposePlaybookOutput.Section(
            "strategy.lookAndFeel", "Where the look should sit",
            "The ground should be a warm off-white, around #F6EFE6.",
            List.of("look.palette.choice"), "medium"));

        assertThat(guard.check(input(Map.of()), with(output, sections)))
            .anySatisfy(v -> assertThat(v).contains("#F6EFE6").contains("never named"));
    }

    @Test
    void exemptsAColourTheClientThemselvesNamed() {
        PurposePlaybookOutput output = complete();
        Map<String, PurposePlaybookOutput.Section> sections =
            new java.util.LinkedHashMap<>(output.sections());
        sections.put("strategy.lookAndFeel", new PurposePlaybookOutput.Section(
            "strategy.lookAndFeel", "Where the look should sit",
            "The existing #0B5FFF must survive, because it is on the vans.",
            List.of("look.mustKeep"), "medium"));

        assertThat(guard.check(
            input(Map.of("look.mustKeep", "the van blue, #0b5fff, stays")),
            with(output, sections)))
            .isEmpty();
    }

    @Test
    void demandsALookPostureOnceTasteWasCaptured() {
        PurposePlaybookOutput output = complete();
        PurposePlaybookOutput withoutPosture = new PurposePlaybookOutput(
            output.engagementId(), output.sections(),
            new PurposePlaybookOutput.LookTerritory(List.of("quiet", "exact"), "  ",
                "warm neutrals", "one serif voice", "a mark that works small", "documentary",
                List.of(), "medium"),
            output.confidence(), output.reasoning(), output.iteration());

        assertThat(guard.check(input(Map.of("look.pairs.wordmark", "wordmark-serif")), withoutPosture))
            .anySatisfy(v -> assertThat(v).contains("lookTerritory").contains("posture"));

        // No taste answers, nothing to forward — silence is correct.
        assertThat(guard.check(input(Map.of()), withoutPosture)).isEmpty();
    }

    @Test
    void reportsAnEmptyConfidenceMap() {
        PurposePlaybookOutput output = complete();
        PurposePlaybookOutput withoutGaps = new PurposePlaybookOutput(
            output.engagementId(), output.sections(), output.lookTerritory(), List.of(),
            output.reasoning(), output.iteration());

        assertThat(guard.check(input(Map.of()), withoutGaps))
            .anySatisfy(v -> assertThat(v).contains("confidence section is empty"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private static PurposePlaybookInput input(Map<String, Object> signals) {
        return new PurposePlaybookInput("eng-1", signals, List.copyOf(signals.keySet()), null, null);
    }

    /** Every required section, each with a body and a source — the shape that must pass clean. */
    private static PurposePlaybookOutput complete() {
        Map<String, PurposePlaybookOutput.Section> sections = new java.util.LinkedHashMap<>();
        for (String key : PurposePlaybookGuard.requiredSectionKeys(STRUCTURE)) {
            sections.put(key, new PurposePlaybookOutput.Section(
                key, titleFor(key), "Written prose for " + key + ".",
                List.of("setup.oneLiner"), "medium"));
        }
        return new PurposePlaybookOutput("eng-1", sections,
            new PurposePlaybookOutput.LookTerritory(List.of("quiet", "exact"), "EVOLVED leaning",
                "warm neutrals", "one serif voice", "a mark that works small", "documentary",
                List.of("the sliders say bold, the pairs say restrained"), "medium"),
            List.of(new PurposePlaybookOutput.Gap("beyond.legacy", "thin",
                "What should still be true in ten years?")),
            "Held back on the competitor critique until a page could be read.", 1);
    }

    private static String titleFor(String key) {
        return key.substring(key.indexOf('.') + 1);
    }

    private static PurposePlaybookOutput with(PurposePlaybookOutput output,
                                              Map<String, PurposePlaybookOutput.Section> sections) {
        return new PurposePlaybookOutput(output.engagementId(), sections, output.lookTerritory(),
            output.confidence(), output.reasoning(), output.iteration());
    }

    /**
     * The module as it sits in the repository. Gradle runs a module's tests from the module
     * directory, so walk up to whichever ancestor holds {@code knowledge/layer1/modules} rather
     * than assuming a depth.
     */
    private static String module(String key) {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("knowledge/layer1/modules").resolve(key);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Layer 1 module not found on any ancestor: " + key);
    }
}
