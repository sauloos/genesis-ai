package ai.genesisbrands.agent.brandbook;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LogoKnockoutTest {

    private static final int INK = 0xff111111;
    private static final int CREAM = 0xfff8f0d8;

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static void fill(BufferedImage img, int x0, int y0, int x1, int y1, int argb) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) img.setRGB(x, y, argb);
    }

    @Test
    void creamLettersInsideAnInkBlockBecomeTransparent() throws IOException {
        BufferedImage img = new BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB);
        fill(img, 10, 10, 90, 50, INK);
        fill(img, 30, 20, 70, 40, CREAM); // a "letter" painted in paper colour

        BufferedImage out = read(LogoKnockout.apply(png(img)));

        assertThat(out.getRGB(50, 30) >>> 24).isZero();
        assertThat(out.getRGB(15, 15)).isEqualTo(INK);
    }

    @Test
    void aPaleMarkOnTransparencyIsTheMarkItselfAndStays() throws IOException {
        BufferedImage img = new BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB);
        fill(img, 10, 10, 90, 50, CREAM);
        byte[] in = png(img);
        assertThat(LogoKnockout.apply(in)).isSameAs(in);
    }

    @Test
    void anInkOnlyLogoOrAnOpaqueImageIsLeftAlone() throws IOException {
        BufferedImage ink = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        fill(ink, 5, 5, 35, 35, INK);
        byte[] in = png(ink);
        assertThat(LogoKnockout.apply(in)).isSameAs(in);

        byte[] opaque = png(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB));
        assertThat(LogoKnockout.apply(opaque)).isSameAs(opaque);
    }
}
