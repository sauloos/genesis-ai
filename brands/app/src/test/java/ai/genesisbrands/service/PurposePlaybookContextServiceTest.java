package ai.genesisbrands.service;

import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the two shapes this service turns a playbook into: the document a prompt reads, and the
 * pillars Layer 2 stores. Both are contracts with something that cannot be unit tested — a model's
 * reading, and a vector search — which is why the shapes themselves are pinned here.
 */
class PurposePlaybookContextServiceTest {

    private final PurposePlaybookContextService service =
        new PurposePlaybookContextService(null, new ObjectMapper());

    @Test
    void theRenderedDocumentCarriesEverySectionWithItsConfidence() {
        String rendered = service.render(playbook(), "This client's Purpose Playbook");

        assertThat(rendered).startsWith("## This client's Purpose Playbook");
        assertThat(rendered).contains("### belief.why — Why you exist (confidence: high)");
        assertThat(rendered).contains("To keep self-builders out of trouble");
        assertThat(rendered).contains("### belong.audience — Who it is for");

        // A section that was never written gets no heading of its own — an empty heading reads as
        // a section that exists and says nothing. It is named once, in the confidence map, as a
        // gap; that is the honest form and it is asserted in the next test.
        assertThat(rendered).doesNotContain("### best.storyArc");
    }

    @Test
    void theRenderedDocumentTellsTheReaderThatAGapIsNotLicenceToInvent() {
        String rendered = service.render(playbook(), "This client's Purpose Playbook");

        // The whole reason the confidence map is rendered: an agent reading a thin field should
        // hedge, not assert a brand fact that rests on a skipped question.
        assertThat(rendered).contains("never as licence to invent");
        assertThat(rendered).contains("### Where this playbook is unsure");
        assertThat(rendered).contains("best.storyArc: thin — would be raised by: one founder story");
    }

    @Test
    void theLookTerritoryKeepsItsTensionsUnresolved() {
        String rendered = service.render(playbook(), "h");

        assertThat(rendered).contains("### The look-and-feel territory");
        assertThat(rendered).contains("Palette: ink and concrete, never magenta");
        // Layer 1 is explicit that a contradiction in the taste answers is reported, not averaged.
        assertThat(rendered).contains("rather than splitting the difference");
        assertThat(rendered).contains("sliders say quiet, the tiles all pick saturated");

        // The same text is what Layer 2 stores, so the two readings cannot drift apart.
        assertThat(service.lookTerritory(playbook())).isPresent();
        assertThat(rendered).contains(service.lookTerritory(playbook()).orElseThrow());
    }

    @Test
    void aPlaybookWithNoLookTerritoryRendersWithoutTheHeading() {
        PurposePlaybookOutput noLook = new PurposePlaybookOutput(
            "eng-1", playbook().sections(), null, List.of(), "reasoning", 1);

        assertThat(service.lookTerritory(noLook)).isEmpty();
        assertThat(service.render(noLook, "h")).doesNotContain("look-and-feel territory");
    }

    @Test
    void pillarsGroupSectionsByTheKeysFirstSegment() {
        List<PurposePlaybookContextService.Pillar> pillars = service.pillars(playbook());

        assertThat(pillars).extracting(PurposePlaybookContextService.Pillar::pillar)
            .containsExactly("belief", "belong");
        assertThat(pillars.get(0).sectionCount()).isEqualTo(2);

        // A pillar's text is self-describing: the section keys and titles travel with the bodies,
        // because a retrieved chunk arrives with no surrounding document.
        assertThat(pillars.get(0).text())
            .contains("belief.why — Why you exist")
            .contains("To keep self-builders out of trouble")
            .contains("belief.neverList")
            .contains("slick, cheap, trendy");
    }

    @Test
    void aSectionWithNoBodyIsNotAPillarOfItsOwn() {
        Map<String, PurposePlaybookOutput.Section> sections = new LinkedHashMap<>(playbook().sections());
        sections.put("beyond.horizon", section("beyond.horizon", "Where next", "   ", "low"));
        PurposePlaybookOutput withBlank = new PurposePlaybookOutput(
            "eng-1", sections, playbook().lookTerritory(), playbook().confidence(), "r", 1);

        // Otherwise an empty pillar would be embedded and returned as precedent that says nothing.
        assertThat(service.pillars(withBlank)).extracting(PurposePlaybookContextService.Pillar::pillar)
            .containsExactly("belief", "belong");
    }

    private static PurposePlaybookOutput playbook() {
        Map<String, PurposePlaybookOutput.Section> sections = new LinkedHashMap<>();
        sections.put("belief.why", section("belief.why", "Why you exist",
            "To keep self-builders out of trouble.", "high"));
        sections.put("belief.neverList", section("belief.neverList", "Never list",
            "slick, cheap, trendy", "high"));
        sections.put("belong.audience", section("belong.audience", "Who it is for",
            "Self-builders mid-project.", null));
        // best.storyArc is deliberately absent but named in the confidence map below.

        PurposePlaybookOutput.LookTerritory look = new PurposePlaybookOutput.LookTerritory(
            List.of("quiet", "structural"), "restrained, confident",
            "ink and concrete, never magenta", "a single grotesque", "wordmark only",
            "site photography, no stock",
            List.of("The sliders say quiet, the tiles all pick saturated asymmetric work"),
            "medium");

        return new PurposePlaybookOutput("eng-1", sections, look,
            List.of(new PurposePlaybookOutput.Gap("best.storyArc", "thin", "one founder story, told once")),
            "reasoning", 1);
    }

    private static PurposePlaybookOutput.Section section(String key, String title, String body, String confidence) {
        return new PurposePlaybookOutput.Section(key, title, body, List.of("setup.oneLiner"), confidence);
    }
}
