package ai.genesisbrands.lookkit;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kit is read by a seed script, by three agents and by a browser, and a slug that resolves
 * nowhere fails silently in all four — a missing tile shows an empty frame, an unknown palette
 * value hands the visual-identity agent nothing. These tests are the build-time guard.
 */
class LookKitTest {

    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /**
     * Words a client-facing label must not use. The capture principle is that a non-designer is
     * never asked to name a design attribute: told the left tile is "serif", they answer with the
     * word rather than their taste. The design term belongs in `form`, which the client never sees.
     */
    private static final List<String> JARGON = List.of(
        "serif", "sans", "grotesk", "grotesque", "geometric", "monospace", "asymmetric",
        "symmetrical", "saturated", "muted", "gradient", "typeface", "kerning", "ligature",
        "palette", "vector");

    private final LookKit kit = new LookKit();

    @Test
    void theEightAxesAreTheOnesTheQuestionnaireAsksAbout() {
        assertThat(kit.catalogue().pairs()).extracting(LookKitCatalogue.Pair::axis)
            .containsExactly("wordmark", "mark", "surface", "imagery",
                             "layout", "ground", "intensity", "composition");
        kit.catalogue().pairs().forEach(pair ->
            assertThat(pair.signalKey()).as(pair.axis()).isEqualTo("look.pairs." + pair.axis()));
    }

    @Test
    void everyValueIsUniqueAcrossTheWholeKit() {
        List<String> values = new ArrayList<>();
        kit.catalogue().pairs().forEach(p -> p.tiles().forEach(t -> values.add(t.value())));
        kit.catalogue().palettes().forEach(p -> values.add(p.value()));
        kit.catalogue().typeSpecimens().forEach(t -> values.add(t.value()));
        assertThat(values).doesNotHaveDuplicates();
        // An answer is only ever a value, so a resolver that cannot find it is a dead brief.
        values.forEach(value -> assertThat(kit.formOf(value)).as(value).isPresent());
    }

    @Test
    void everySpecimenIsAnOriginatedSvgThatShipsWithTheKit() throws Exception {
        for (LookKitCatalogue.Pair pair : kit.catalogue().pairs()) {
            for (LookKitCatalogue.Tile tile : pair.tiles()) {
                assertThat(tile.imageUrl()).as(tile.value())
                    .isEqualTo("/look-kit/pairs/" + tile.value() + ".svg");
                String svg = read("/static" + tile.imageUrl());
                assertThat(svg).as(tile.value()).contains("<svg").contains("viewBox=\"0 0 320 220\"")
                    .contains("xmlns=\"http://www.w3.org/2000/svg\"")
                    .contains("role=\"img\"").contains("aria-label=");
                // No screenshots and no real brands: a specimen that pulled in an external
                // image or font would be neither originated nor offline-safe. The namespace
                // declaration is the one URL allowed, so it comes out before the check.
                String withoutNamespace = svg.replace("http://www.w3.org/2000/svg", "");
                assertThat(withoutNamespace).as(tile.value())
                    .doesNotContain("<image").doesNotContain("http").doesNotContain("@import");
            }
        }
    }

    @Test
    void eachPairIsAForcedChoiceBetweenTwoDistinctForms() {
        kit.catalogue().pairs().forEach(pair -> {
            assertThat(pair.a().form()).as(pair.axis()).isNotEqualTo(pair.b().form());
            assertThat(pair.a().label()).as(pair.axis()).isNotEqualTo(pair.b().label());
        });
    }

    @Test
    void everyPaletteCarriesFiveValidHexesAddressableByRole() {
        kit.catalogue().palettes().forEach(palette -> {
            assertThat(palette.colors()).as(palette.value()).hasSize(5);
            palette.colors().forEach(colour ->
                assertThat(colour).as(palette.value()).matches(HEX));
            assertThat(palette.roles()).as(palette.value())
                .containsOnlyKeys("ground", "support", "primary", "secondary", "ink");
            // A role must name a colour from the strip the client actually saw.
            assertThat(palette.colors()).as(palette.value())
                .containsAll(palette.roles().values());
            assertThat(palette.note()).as(palette.value()).isNotBlank();
        });
    }

    @Test
    void everySpectrumIsDrawableAndBelongsToASliderSignal() {
        assertThat(kit.catalogue().spectra()).isNotEmpty();
        kit.catalogue().spectra().forEach((signalKey, colours) -> {
            assertThat(signalKey).as("spectrum key").startsWith("look.slider.");
            assertThat(colours).as(signalKey).hasSizeGreaterThan(1);
            colours.forEach(colour -> assertThat(colour).as(signalKey).matches(HEX));
        });
    }

    @Test
    void everySpecimenSetsTheClientsOwnSentenceAndDegradesGracefully() {
        kit.catalogue().typeSpecimens().forEach(specimen -> {
            assertThat(specimen.sampleText()).as(specimen.value()).contains("{setup.oneLiner}");
            assertThat(specimen.fallbackText()).as(specimen.value()).isNotBlank()
                .doesNotContain("{");
            assertThat(specimen.fontFamily()).as(specimen.value()).contains(",");
        });
    }

    @Test
    void noClientFacingLabelNamesADesignAttribute() {
        List<String> shown = new ArrayList<>();
        kit.catalogue().pairs().forEach(p -> p.tiles().forEach(t -> shown.add(t.label())));
        kit.catalogue().palettes().forEach(p -> { shown.add(p.label()); shown.add(p.note()); });
        kit.catalogue().typeSpecimens().forEach(t -> shown.add(t.label()));
        for (String label : shown) {
            String lower = label.toLowerCase(Locale.ROOT);
            assertThat(JARGON).as(label).noneMatch(lower::contains);
        }
    }

    private String read(String resource) throws Exception {
        try (InputStream in = LookKit.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
