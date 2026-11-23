package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Covers the two halves of the derivation that do not need a model: what the prompt is built from,
 * and what the output is turned into. The model call between them is the one part a test cannot
 * pin, which is exactly why the parts either side of it are pinned here.
 */
class PurposePlaybookBriefDerivationTest {

    private static Layer1Service layer1;
    private static PurposePlaybookBriefDerivation derivation;

    @BeforeAll
    static void setUp() {
        layer1 = new Layer1Service();
        layer1.load();
        // Only assemblePrompt and parse are exercised, and neither touches the model, the agent
        // config or retrieval — a built ChatClient is never asked for.
        derivation = new PurposePlaybookBriefDerivation(
            mock(ChatClient.Builder.class), null, new ObjectMapper(), layer1, null);
    }

    @Test
    void theReasoningModulesAreLoaded() {
        // The ancestor search in Layer1Service is what makes this work from a subproject's working
        // directory; a zero-module load used to fail far away from its cause.
        assertThat(layer1.listModules()).containsKeys(
            "directions/purpose-to-direction.yaml", "directions/archetypes.yaml");
    }

    @Test
    void thePromptCarriesTheModuleTheSectionsAndTheGaps() {
        String prompt = derivation.assemblePrompt(playbook(), List.of("a precedent chunk"));

        // The method, not a paraphrase of it.
        assertThat(prompt).contains("module: purpose-to-direction");
        assertThat(prompt).contains("module: brand-archetypes");

        // Every section that was written, with its confidence.
        assertThat(prompt).contains("### strategy.positioningSummary");
        assertThat(prompt).contains("(confidence: high)");
        assertThat(prompt).contains("A plain-dealing structural engineering practice");
        assertThat(prompt).contains("### belief.neverList");

        // And every section that was not, named as a gap rather than left to be invented.
        assertThat(prompt).contains("Sections not written in this playbook");
        assertThat(prompt).contains("- best.storyArc");

        // The typed look territory, including the tension the module says never to average away.
        assertThat(prompt).contains("Posture: restrained, confident");
        assertThat(prompt).contains("never magenta");
        assertThat(prompt).contains("sliders say quiet, the tiles all pick saturated");

        // The confidence register, so a direction cannot bet everything on a thin field.
        assertThat(prompt).contains("best.storyArc: thin");
        assertThat(prompt).contains("a precedent chunk");
        assertThat(prompt).contains("Craft reference only");
    }

    @Test
    void theRawAnswersAreNeverInThePrompt() {
        // purpose-to-direction.yaml's input contract forbids re-reading the questionnaire, and the
        // type signature is what enforces it: there is no way to pass answers to this class. This
        // test documents that the enforcement is deliberate rather than an oversight.
        assertThat(PurposePlaybookBriefDerivation.class.getDeclaredMethods())
            .noneMatch(m -> m.toGenericString().contains("QuestionnaireAnswer"));
    }

    @Test
    void parseBuildsAllThreeDirectionsInOrderWithTheSharedInvariants() {
        List<DirectionBrief> briefs = derivation.parse("eng-1", modelOutput());

        assertThat(briefs).extracting(DirectionBrief::direction).containsExactly(
            DirectionBrief.CreativeDirection.ANCHORED,
            DirectionBrief.CreativeDirection.EVOLVED,
            DirectionBrief.CreativeDirection.DISRUPTIVE);

        // The foundation is shared, so it is parsed once and attached to all three.
        assertThat(briefs).allSatisfy(b -> {
            assertThat(b.engagementId()).isEqualTo("eng-1");
            assertThat(b.foundation().corePositioning()).isEqualTo("The engineer who says no");
            assertThat(b.brand().name()).isEqualTo("Hartwell Structural");
            assertThat(b.brand().differentiator()).isEqualTo("refuses work it cannot stand behind");
            // The invariants are given once at the top level and inherited, so they cannot drift
            // between directions — which is the whole point of calling them invariants.
            assertThat(b.purpose().invariants())
                .containsExactly("Never use the words slick, cheap or trendy", "Never magenta");
        });

        DirectionBrief disruptive = briefs.get(2);
        assertThat(disruptive.purpose().summary()).isEqualTo("The practice that publishes its refusals");
        assertThat(disruptive.purpose().archetype()).isEqualTo("The Rebel");
        assertThat(disruptive.purpose().risk()).isEqualTo("Reads as arrogant to a cautious buyer");
        assertThat(disruptive.purpose().releases()).containsExactly("The institutional tone");
        assertThat(disruptive.purpose().derivedFrom()).containsExactly("strategy.directionGuidance");
        assertThat(disruptive.brand().personality()).containsExactly("blunt", "exact");
        assertThat(disruptive.trainingInstructions()).containsExactly("Lead with the refusal, not the service list");

        // audienceEmphasis is what BrandContext.targetAudience carries, because a direction that
        // leans on one segment has to brief the specialists on that segment, not the shared one.
        assertThat(disruptive.brand().targetAudience()).isEqualTo("Self-builders burned once already");
    }

    @Test
    void theDerivationIsRenderedIntoAdditionalContextForTheFiveSpecialists() {
        // None of the five specialist prompts has been taught to read the typed purpose record, so
        // additionalContext is how this material actually reaches them. If this stops being
        // populated, the richer derivation silently becomes dead data.
        DirectionBrief anchored = derivation.parse("eng-1", modelOutput()).get(0);
        String context = anchored.additionalContext();

        assertThat(context).contains("Derived from this client's Purpose Playbook");
        assertThat(context).contains("Direction in one sentence: The practice you already are, said properly");
        assertThat(context).contains("The tension it resolves: The work is trusted, the brand is not");
        assertThat(context).contains("Primary archetype: The Sage");
        assertThat(context).contains("What it risks: Too little change to be noticed");
        assertThat(context).contains("Retains:\n- The surveyor referrals");
        assertThat(context).contains("Constraints that hold across all three directions");
        assertThat(context).contains("- Never magenta");
    }

    @Test
    void aMissingDirectionFailsRatherThanShippingTwo() {
        String twoOnly = """
            {
              "foundation": {"corePositioning": "x"},
              "directions": [
                {"direction": "ANCHORED", "brand": {"name": "n"}},
                {"direction": "EVOLVED", "brand": {"name": "n"}}
              ]
            }
            """;
        // A two-direction engagement would break the client's promised choice of three and every
        // downstream surface that counts on all three, so it fails loudly here instead.
        assertThatThrownBy(() -> derivation.parse("eng-1", twoOnly))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing the DISRUPTIVE direction");
    }

    @Test
    void aFencedResponseStillParses() {
        List<DirectionBrief> briefs =
            derivation.parse("eng-1", "```json\n" + modelOutput() + "\n```");
        assertThat(briefs).hasSize(3);
    }

    private static PurposePlaybookOutput playbook() {
        Map<String, PurposePlaybookOutput.Section> sections = new LinkedHashMap<>();
        sections.put("cover", section("cover", "Cover", "Hartwell Structural — structural engineering", "high"));
        sections.put("strategy.positioningSummary", section("strategy.positioningSummary", "The brief in one page",
            "A plain-dealing structural engineering practice for self-builders.", "high"));
        sections.put("belief.neverList", section("belief.neverList", "Never list",
            "slick, cheap, trendy", "high"));
        sections.put("strategy.lookAndFeel", section("strategy.lookAndFeel", "Where your taste sits",
            "Quiet, structural, never magenta.", "medium"));
        // best.storyArc is deliberately absent — several playbook sections are optional, and the
        // prompt has to name the gap rather than let the model fill it.

        PurposePlaybookOutput.LookTerritory look = new PurposePlaybookOutput.LookTerritory(
            List.of("quiet", "structural"),
            "restrained, confident",
            "ink and concrete, never magenta",
            "a single grotesque",
            "wordmark only",
            "site photography, no stock",
            List.of("The sliders say quiet, the tiles all pick saturated asymmetric work"),
            "medium");

        List<PurposePlaybookOutput.Gap> confidence = List.of(
            new PurposePlaybookOutput.Gap("best.storyArc", "thin", "one founder story, told once"));

        return new PurposePlaybookOutput("eng-1", sections, look, confidence, "reasoning", 1);
    }

    private static PurposePlaybookOutput.Section section(String key, String title, String body, String confidence) {
        return new PurposePlaybookOutput.Section(key, title, body, List.of("setup.oneLiner"), confidence);
    }

    private static String modelOutput() {
        return """
            {
              "foundation": {
                "differentiator": "refuses work it cannot stand behind",
                "targetAudiencePersona": "Self-builders mid-project",
                "corePositioning": "The engineer who says no",
                "toneSpectrum": "plain to blunt, never salesy"
              },
              "invariants": ["Never use the words slick, cheap or trendy", "Never magenta"],
              "directions": [
                {
                  "direction": "ANCHORED",
                  "summary": "The practice you already are, said properly",
                  "tension": "The work is trusted, the brand is not",
                  "archetype": "The Sage",
                  "tonalRegister": "plain, unhurried",
                  "visualTerritory": "ink on paper, drawing-office restraint",
                  "retains": ["The surveyor referrals", "The existing wordmark"],
                  "releases": ["The stock photography"],
                  "audienceEmphasis": "Self-builders mid-project",
                  "bet": "That credibility already exists and only needs showing",
                  "risk": "Too little change to be noticed",
                  "derivedFrom": ["strategy.positioningSummary", "cover"],
                  "brand": {
                    "name": "Hartwell Structural",
                    "industry": "Structural engineering",
                    "coreOffer": "Structural design for domestic builds",
                    "personality": ["plain", "exact", "unhurried"],
                    "tone": "quietly certain"
                  },
                  "trainingInstructions": ["Keep the existing wordmark legible at small sizes"]
                },
                {
                  "direction": "EVOLVED",
                  "summary": "The practice that explains itself",
                  "tension": "Expertise read as gatekeeping",
                  "archetype": "The Sage",
                  "tonalRegister": "plain, generous",
                  "visualTerritory": "diagrammatic, annotated",
                  "retains": ["The plain language"],
                  "releases": ["The institutional grey"],
                  "audienceEmphasis": "First-time self-builders",
                  "bet": "That teaching wins the brief",
                  "risk": "Could read as junior",
                  "derivedFrom": ["strategy.positioningSummary"],
                  "brand": {
                    "name": "Hartwell Structural",
                    "industry": "Structural engineering",
                    "coreOffer": "Structural design, explained as it goes",
                    "personality": ["generous", "exact"],
                    "tone": "patient"
                  },
                  "trainingInstructions": ["Every claim gets a worked example"]
                },
                {
                  "direction": "DISRUPTIVE",
                  "summary": "The practice that publishes its refusals",
                  "tension": "Everyone claims integrity, nobody evidences it",
                  "archetype": "The Rebel",
                  "tonalRegister": "blunt",
                  "visualTerritory": "high-contrast, documentary",
                  "retains": ["The never-list"],
                  "releases": ["The institutional tone"],
                  "audienceEmphasis": "Self-builders burned once already",
                  "bet": "That saying no in public is the proof",
                  "risk": "Reads as arrogant to a cautious buyer",
                  "derivedFrom": ["strategy.directionGuidance"],
                  "brand": {
                    "name": "Hartwell Structural",
                    "industry": "Structural engineering",
                    "coreOffer": "Structural design, with the jobs we turned down on the record",
                    "personality": ["blunt", "exact"],
                    "tone": "unapologetic"
                  },
                  "trainingInstructions": ["Lead with the refusal, not the service list"]
                }
              ]
            }
            """;
    }
}
