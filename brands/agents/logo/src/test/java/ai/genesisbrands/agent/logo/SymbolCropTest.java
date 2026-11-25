package ai.genesisbrands.agent.logo;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolCropTest {

    private static final Color INK = new Color(0x1C, 0x1A, 0x17);
    private static final Color MOSS = new Color(0x4B, 0x5D, 0x3A);

    @Test
    void stackedLockupKeepsTheSymbolAboveTheWordmark() throws Exception {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        g.setColor(MOSS);
        g.fillOval(412, 250, 200, 200);                 // symbol
        g.setColor(INK);
        for (int i = 0; i < 9; i++) g.fillRect(262 + i * 56, 520, 40, 60);   // a line of "letters"
        g.dispose();

        BufferedImage symbol = read(SymbolCrop.crop(png(img)));
        assertThat(symbol.getWidth()).isEqualTo(symbol.getHeight());
        assertThat(colours(symbol)).contains(MOSS.getRGB()).doesNotContain(INK.getRGB());
    }

    @Test
    void horizontalLockupKeepsTheTallLeadingBlock() throws Exception {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        g.setColor(MOSS);
        g.fillRect(150, 420, 120, 120);                 // symbol, taller than the letters
        g.setColor(INK);
        for (int i = 0; i < 8; i++) g.fillRect(310 + i * 70, 450, 50, 60);
        g.dispose();

        BufferedImage symbol = read(SymbolCrop.crop(png(img)));
        assertThat(colours(symbol)).contains(MOSS.getRGB()).doesNotContain(INK.getRGB());
    }

    @Test
    void dropsALetterTheSymbolOverhangs() throws Exception {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        g.setColor(MOSS);
        g.fillRect(150, 400, 40, 160);                  // symbol stem, taller than the letters
        g.fillRect(150, 400, 150, 20);                  // arm reaching over the first letter
        g.setColor(INK);
        g.fillRect(260, 450, 30, 60);                   // first letter, under the arm's columns
        for (int i = 0; i < 7; i++) g.fillRect(330 + i * 70, 450, 50, 60);
        g.dispose();

        BufferedImage symbol = read(SymbolCrop.crop(png(img)));
        assertThat(colours(symbol)).contains(MOSS.getRGB()).doesNotContain(INK.getRGB());
    }

    @Test
    void ignoresSpecksTheModelLeftBehind() throws Exception {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        g.setColor(MOSS);
        g.fillRect(150, 420, 120, 120);
        g.setColor(INK);
        for (int i = 0; i < 8; i++) g.fillRect(310 + i * 70, 450, 50, 60);
        g.fillRect(1000, 1000, 3, 3);                   // a stray speck far from the lockup
        g.dispose();

        assertThat(SymbolCrop.crop(png(img))).isPresent();
    }

    @Test
    void textOnlyOrOpaqueImagesAreLeftToTheFallback() throws Exception {
        BufferedImage text = canvas();
        Graphics2D g = text.createGraphics();
        g.setColor(INK);
        for (int i = 0; i < 8; i++) g.fillRect(200 + i * 70, 450, 50, 60);
        g.dispose();
        assertThat(SymbolCrop.crop(png(text))).isEmpty();

        BufferedImage opaque = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        assertThat(SymbolCrop.crop(png(opaque))).isEmpty();
    }

    /** Manual check against real lockups: -DsymbolCrop.dir=<folder of PNGs> writes <name>.crop.png. */
    @Test
    void cropsAFolderOfRealLockupsWhenAsked() throws Exception {
        String dir = System.getProperty("symbolCrop.dir", "");
        if (dir.isBlank()) return;
        File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".png") && !n.endsWith(".crop.png"));
        for (File f : files == null ? new File[0] : files) {
            Optional<byte[]> out = SymbolCrop.crop(Files.readAllBytes(f.toPath()));
            System.out.println("symbolCrop " + f.getName() + " -> " + (out.isPresent() ? "cropped" : "no clean split"));
            if (out.isPresent()) Files.write(new File(dir, f.getName().replace(".png", ".crop.png")).toPath(), out.get());
        }
    }

    private static BufferedImage canvas() {
        return new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(Optional<byte[]> png) throws Exception {
        assertThat(png).isPresent();
        return ImageIO.read(new ByteArrayInputStream(png.get()));
    }

    /** The distinct fully opaque colours in the image (edge pixels are blended, so skipped). */
    private static java.util.Set<Integer> colours(BufferedImage img) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) == 0xFF) set.add(argb);
            }
        return set;
    }
}
