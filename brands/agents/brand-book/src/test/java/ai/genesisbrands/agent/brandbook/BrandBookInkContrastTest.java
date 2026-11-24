package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer.Fallback;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput.ColorSwatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every template paints its labels, headings, section numbers and statement pull-quote in the
 * brand's primary on the white page. A palette whose primary is a pale cream renders those
 * invisible, which is what the Kindling Retrofit book did (2026-11-23), so the renderer darkens
 * the primary into a {@code --primary-ink} that is guaranteed legible.
 */
class BrandBookInkContrastTest {

    @Test
    void palePrimariesAreDarkenedUntilLegible() {
        for (String pale : new String[]{"#f5efdc", "#ffffff", "#fdf6e3", "#e8e0c8", "#c9f5a0", "#ffeb3b"}) {
            String ink = BrandBookTemplateRenderer.inkOnWhite(pale);
            assertTrue(contrast(ink) >= 4.5,
                pale + " became " + ink + " at only " + String.format("%.2f", contrast(ink)) + ":1");
        }
    }

    @Test
    void alreadyLegiblePrimariesAreLeftExactlyAsChosen() {
        for (String dark : new String[]{"#1f3a5f", "#7a2e2e", "#2d4739", "#000000", "#595959"}) {
            assertEquals(dark, BrandBookTemplateRenderer.inkOnWhite(dark),
                dark + " already passes and must not be shifted");
        }
    }

    @Test
    void theInkKeepsTheBrandHue() {
        // A pale terracotta must still read as terracotta, not as a neutral grey.
        String ink = BrandBookTemplateRenderer.inkOnWhite("#f0c0a8");
        int r = Integer.parseInt(ink.substring(1, 3), 16);
        int g = Integer.parseInt(ink.substring(3, 5), 16);
        int b = Integer.parseInt(ink.substring(5, 7), 16);
        assertTrue(r > g && g > b, "channel order lost: " + ink);
    }

    @Test
    void aMalformedHexStillYieldsSomethingLegible() {
        assertTrue(contrast(BrandBookTemplateRenderer.inkOnWhite(null)) >= 4.5);
        assertTrue(contrast(BrandBookTemplateRenderer.inkOnWhite("not-a-colour")) >= 4.5);
    }

    /** The palette that produced the invisible tagline: primary is the paper, 1.17:1 on white. */
    private static final List<ColorSwatch> KINDLING_ANCHORED = List.of(
        new ColorSwatch("Paper Ground",    "#F3ECE1", "primary",   ""),
        new ColorSwatch("Clay",            "#C9A789", "secondary", ""),
        new ColorSwatch("Sand",            "#E3D4BE", "secondary", ""),
        new ColorSwatch("Rust",            "#B5542F", "accent",    ""),
        new ColorSwatch("Bark Ink",        "#4A3B30", "neutral",   ""),
        new ColorSwatch("Weathered Stone", "#A69A8B", "neutral",   ""));

    @Test
    void aPalePrimaryBorrowsThePalettesOwnInk() {
        // Not the mechanically darkened paper (#78746f, a characterless grey) — the deep brown
        // the designer put in the palette and named for ink.
        assertEquals("#4A3B30", BrandBookTemplateRenderer.legibleOnWhite("#F3ECE1", KINDLING_ANCHORED, 4.5, Fallback.DEEPEST));
    }

    @Test
    void aLegiblePrimaryIsUsedAsIsEvenWhenDarkerSwatchesExist() {
        List<ColorSwatch> evolved = List.of(
            new ColorSwatch("Clay", "#8A4A2A", "primary", ""),
            new ColorSwatch("Ink",  "#2E2520", "neutral", ""));
        assertEquals("#8A4A2A", BrandBookTemplateRenderer.legibleOnWhite("#8A4A2A", evolved, 4.5, Fallback.DEEPEST));
    }

    @Test
    void aPalePaletteWithNoInkAtAllStillYieldsLegibleType() {
        List<ColorSwatch> allPale = List.of(
            new ColorSwatch("Paper", "#F3ECE1", "primary",   ""),
            new ColorSwatch("Sand",  "#E3D4BE", "secondary", ""));
        String ink = BrandBookTemplateRenderer.legibleOnWhite("#F3ECE1", allPale, 4.5, Fallback.DEEPEST);
        assertTrue(contrast(ink) >= 4.5, "fell back to " + ink + " at " + contrast(ink) + ":1");
    }

    @Test
    void aMissingPaletteDoesNotBreakTheRender() {
        assertTrue(contrast(BrandBookTemplateRenderer.legibleOnWhite("#F3ECE1", null, 4.5, Fallback.DEEPEST)) >= 4.5);
        assertTrue(contrast(BrandBookTemplateRenderer.legibleOnWhite("#F3ECE1", List.of(), 4.5, Fallback.DEEPEST)) >= 4.5);
    }

    /**
     * The two thresholds exist so a good mid-tone brand colour keeps its covers and sidebars.
     * Kindling Retrofit's EVOLVED clay is 3.51:1 — right for white display type on a fill, too
     * weak for a caption on white. One threshold for both would turn that cover near-black.
     */
    @Test
    void aMidToneBrandColourKeepsItsPanelsAndOnlyLosesItsSmallType() {
        List<ColorSwatch> evolved = List.of(
            new ColorSwatch("Paper Ground", "#F4EEE3", "neutral",   ""),
            new ColorSwatch("Clay",         "#B97A56", "primary",   ""),
            new ColorSwatch("Sand",         "#D9C4A3", "secondary", ""),
            new ColorSwatch("Rust",         "#9C4A2E", "accent",    ""),
            new ColorSwatch("Ink",          "#2E2520", "neutral",   ""));

        assertEquals("#B97A56", BrandBookTemplateRenderer.legibleOnWhite("#B97A56", evolved, 3.0, Fallback.MOST_COLOUR),
            "the clay cover and sidebar must stay clay");
        assertEquals("#2E2520", BrandBookTemplateRenderer.legibleOnWhite("#B97A56", evolved, 4.5, Fallback.DEEPEST),
            "clay captions on white are below AA and must fall back to the palette's ink");
    }

    /**
     * The two fallbacks pull in opposite directions on the same palette, which is the whole reason
     * they are separate: the heading wants the bark the designer named for ink, the cover wants the
     * rust, and neither would be right in the other's place.
     */
    @Test
    void theInkTakesThePalettesDeepestPassingSwatchAndThePanelItsMostColourfulOne() {
        assertEquals("#4A3B30",
            BrandBookTemplateRenderer.legibleOnWhite("#F3ECE1", KINDLING_ANCHORED, 4.5, Fallback.DEEPEST),
            "type must fall back to Bark Ink, not to the rust accent");
        assertEquals("#B5542F",
            BrandBookTemplateRenderer.legibleOnWhite("#F3ECE1", KINDLING_ANCHORED, 3.0, Fallback.MOST_COLOUR),
            "a cover or sidebar must keep Rust rather than flatten to Bark Ink");
    }

    /** WCAG 2.1 contrast of a hex colour against white, computed independently of the renderer. */
    private static double contrast(String hex) {
        double[] lin = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            lin[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        double l = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
        return 1.05 / (l + 0.05);
    }
}
