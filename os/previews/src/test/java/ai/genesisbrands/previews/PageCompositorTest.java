package ai.genesisbrands.previews;

import ai.genesisbrands.service.DocumentPreviewProvider.Access;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageCompositorTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** A pale page with fine black stripes standing in for body text. */
    static byte[] page(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        for (int y = 40; y < h - 80; y += 6) g.fillRect(40, y, w - 80, 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] b) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(b));
    }

    /** Spread between the lightest and darkest luminance on a column through the stripes. */
    private static int contrast(BufferedImage img, int x, int y0, int y1) {
        int min = 255, max = 0;
        for (int y = y0; y < y1; y++) {
            int rgb = img.getRGB(x, y);
            int lum = (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3;
            min = Math.min(min, lum);
            max = Math.max(max, lum);
        }
        return max - min;
    }

    @Test
    void aShownPageIsScaledToTheWidthCapAndStillReadable() throws Exception {
        BufferedImage out = decode(PageCompositor.compose(page(1800, 2400), Access.SHOW, "sa***@x.com", "ABCD2345", "2026-11-28", 1200));
        assertThat(out.getWidth()).isEqualTo(1200);
        assertThat(out.getHeight()).isEqualTo(1600);
        assertThat(contrast(out, 600, 300, 360)).isGreaterThan(120);  // stripes survive a normal view
    }

    @Test
    void aBlurredPageLosesItsFineDetail() throws Exception {
        BufferedImage out = decode(PageCompositor.compose(page(1200, 1600), Access.BLUR, "sa***@x.com", "ABCD2345", "2026-11-28", 1200));
        assertThat(contrast(out, 600, 300, 360)).isLessThan(60);
    }

    @Test
    void theWatermarkIsOnTheImage() throws Exception {
        byte[] base = page(1200, 1600);
        BufferedImage plain = decode(base);
        BufferedImage marked = decode(PageCompositor.compose(base, Access.SHOW, "sa***@x.com", "ABCD2345", "2026-11-28", 1200));
        // The margin of the base page is pure white; the watermark must have darkened part of it.
        int changed = 0;
        for (int y = 0; y < 30; y++) for (int x = 0; x < 1200; x += 3) if (plain.getRGB(x, y) != marked.getRGB(x, y)) changed++;
        assertThat(changed).isPositive();
        // And the footer band is dark across the bottom edge.
        assertThat(marked.getRGB(600, 1599) & 0xff).isLessThan(140);
    }

    @Test
    void twoViewCodesProduceDifferentPatterns() throws Exception {
        byte[] base = page(1200, 1600);
        byte[] a = PageCompositor.compose(base, Access.SHOW, "sa***@x.com", "AAAAAAAA", "2026-11-28", 1200);
        byte[] b = PageCompositor.compose(base, Access.SHOW, "sa***@x.com", "BBBBBBBB", "2026-11-28", 1200);
        assertThat(a).isNotEqualTo(b);
        assertThat(PageCompositor.phase("AAAAAAAA", 700, 200)).isNotEqualTo(PageCompositor.phase("BBBBBBBB", 700, 200));
        assertThat(PageCompositor.phase("AAAAAAAA", 700, 200)).isEqualTo(PageCompositor.phase("AAAAAAAA", 700, 200));
    }

    @Test
    void aWithheldPageIsNeverRendered() {
        assertThatThrownBy(() -> PageCompositor.compose(new byte[]{1}, Access.WITHHOLD, "i", "c", "d", 1200))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnreadableBaseFailsInsteadOfServingAnything() {
        assertThatThrownBy(() -> PageCompositor.compose(new byte[]{1, 2, 3}, Access.SHOW, "i", "c", "d", 1200))
            .isInstanceOf(java.io.IOException.class);
    }
}
