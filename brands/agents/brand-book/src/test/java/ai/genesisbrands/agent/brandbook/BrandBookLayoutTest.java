package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput.ColorSwatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** The page numbering, contents, colour and don'ts logic the renderer builds a book with. */
class BrandBookLayoutTest {

    private static final String BOOK =
        "<section class=\"page cover\"><div class=\"ghost\">99</div></section>"
            + "<section data-toc=\"Logo\" class=\"page\"><span class=\"footer-page\">22</span></section>"
            + "<section class=\"page\"><span class=\"footer-page\">22</span></section>"
            + "<section data-toc=\"Colour\" class=\"page\"><span class=\"footer-page\">22</span></section>";

    @Test
    void numbersEveryPageByItsRealPosition() {
        String html = BrandBookTemplateRenderer.paginate(BOOK);
        assertThat(html).contains("<div class=\"ghost\">01<")
            .contains("<span class=\"footer-page\">02<")
            .contains("<span class=\"footer-page\">03<")
            .contains("<span class=\"footer-page\">04<")
            .doesNotContain(">22<");
    }

    @Test
    void contentsListsTheChapterPagesWithTheirNumbers() {
        assertThat(BrandBookTemplateRenderer.buildContentsHtml(BOOK)).isEqualTo(
            "<div class=\"contents-item\"><div class=\"contents-num\">02</div><div class=\"contents-name\">Logo</div></div>"
                + "<div class=\"contents-item\"><div class=\"contents-num\">04</div><div class=\"contents-name\">Colour</div></div>");
    }

    private static ColorSwatch swatch(String name, String hex, String role) {
        return new ColorSwatch(name, hex, role, "", null, null, null);
    }

    @Test
    void aReservedAccentDoesNotBecomeTheWorkingAccent() {
        List<ColorSwatch> palette = List.of(
            swatch("Pine", "#1F3A27", "primary"),
            swatch("Stamp Red", "#C0392B", "accent — reserved for the roast-date stamp only"),
            swatch("Brass", "#B08D57", "accent"));
        assertThat(BrandBookTemplateRenderer.findSwatchHex(palette, "accent", 2)).isEqualTo("#B08D57");
        assertThat(BrandBookTemplateRenderer.findSwatchHex(palette.subList(0, 2), "accent", 1)).isEqualTo("#C0392B");
        assertThat(BrandBookTemplateRenderer.findSwatchHex(palette, "neutral", 0)).isEqualTo("#1F3A27");
        assertThat(BrandBookTemplateRenderer.findSwatchHex(List.of(), "primary", 0)).isEqualTo("#1a1a1a");
    }

    @Test
    void findsThePaletteColoursADeviceNames() {
        List<ColorSwatch> palette = List.of(
            swatch("Deep Pine Green", "#1F3A27", "primary"),
            swatch("Pale Cream", "#F2E8D5", "neutral"),
            swatch("Roast Brown", "#5A3A22", "secondary"));
        assertThat(BookBlocks.coloursNamed("1px rule in deep pine, on Pale Cream", palette))
            .containsExactly("#1F3A27", "#F2E8D5");
        assertThat(BookBlocks.coloursNamed("Roast-Brown fill, deep pine green keyline", palette))
            .containsExactly("#5A3A22", "#1F3A27");
        assertThat(BookBlocks.coloursNamed("no colours here", palette)).isEmpty();
    }

    @Test
    void eachDontIsShownByTheMisuseItNamesFirst() {
        assertThat(BookBlocks.matchDemos(List.of(
            "Don't stretch the logo, or rotate it.",
            "Don't rotate the logo off its baseline.",
            "Never place the wordmark over a busy photograph.",
            "Never write it in lowercase.")))
            .containsExactly(BookBlocks.Demo.STRETCH, BookBlocks.Demo.ROTATE, BookBlocks.Demo.BUSY, null);
    }

    @Test
    void sharedComponentCssCannotCloseItsStyleElement() {
        String css = BrandBookTemplateRenderer.componentsCss();
        assertThat(css).contains(".bb-donts");
        assertThat(css.toLowerCase(Locale.ROOT)).doesNotContain("</style");
        assertThat(Arrays.asList(css.split("\n"))).isNotEmpty();
    }
}
