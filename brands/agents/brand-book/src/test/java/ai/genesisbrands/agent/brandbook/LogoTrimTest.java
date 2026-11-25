package ai.genesisbrands.agent.brandbook;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LogoTrimTest {

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void aWordmarkOnAWideCanvasIsCroppedToTheMarkPlusRoom() throws IOException {
        BufferedImage canvas = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB);
        for (int y = 450; y < 550; y++) for (int x = 100; x < 900; x++) canvas.setRGB(x, y, 0xff112233);
        canvas.setRGB(5, 5, 0x08ffffff); // faint haze far from the mark

        BufferedImage out = read(LogoTrim.trim(png(canvas)));

        // 800 x 100 mark, padded by 4% of 800 = 32 on every side
        assertThat(out.getWidth()).isEqualTo(864);
        assertThat(out.getHeight()).isEqualTo(164);
        assertThat(out.getRGB(32, 32)).isEqualTo(0xff112233);
    }

    @Test
    void aLogoThatAlreadyFillsItsCanvasIsLeftAlone() throws IOException {
        BufferedImage full = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 98; y++) for (int x = 2; x < 198; x++) full.setRGB(x, y, 0xff000000);
        byte[] in = png(full);
        assertThat(LogoTrim.trim(in)).isSameAs(in);
    }

    @Test
    void anOpaqueOrUnreadableImageIsLeftAlone() throws IOException {
        byte[] opaque = png(new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB));
        assertThat(LogoTrim.trim(opaque)).isSameAs(opaque);
        byte[] junk = {1, 2, 3};
        assertThat(LogoTrim.trim(junk)).isSameAs(junk);
    }
}
