package ai.genesisbrands.agent.brandbook;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Crops a transparent logo PNG to what can be seen, so it fills the slot a template gives it.
 * A generated logo sits on a square canvas whose wordmark may cover a tenth of it, and the
 * slots are sized for the mark, not the canvas.
 */
final class LogoTrim {

    /** Alpha at or below this is noise around the mark, not part of it. */
    static final int ALPHA_FLOOR = 16;
    /** Room kept around the mark, as a share of its longer side. */
    static final double PAD = 0.04;

    private LogoTrim() {}

    /** The PNG cropped to its visible pixels plus a little room; {@code png} unchanged when there is nothing to gain. */
    static byte[] trim(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null || !img.getColorModel().hasAlpha()) return png;
            int w = img.getWidth(), h = img.getHeight();
            int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((img.getRGB(x, y) >>> 24) > ALPHA_FLOOR) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < 0) return png;
            int pad = (int) Math.round(Math.max(maxX - minX + 1, maxY - minY + 1) * PAD);
            int x0 = Math.max(0, minX - pad), y0 = Math.max(0, minY - pad);
            int x1 = Math.min(w, maxX + 1 + pad), y1 = Math.min(h, maxY + 1 + pad);
            if ((long) (x1 - x0) * (y1 - y0) > 0.9 * w * h) return png;

            BufferedImage out = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_ARGB);
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int argb = img.getRGB(x, y);
                    // Drop the faint haze too, so it can't show once the logo is whited out.
                    out.setRGB(x - x0, y - y0, (argb >>> 24) > ALPHA_FLOOR ? argb : 0);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            return png;
        }
    }
}
