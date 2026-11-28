package ai.genesisbrands.previews;

import ai.genesisbrands.service.DocumentPreviewProvider.Access;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Turns a stored page image into the one rendition a viewer receives: scaled down so it is
 * good to read but poor to reuse, blurred when the page policy says so, and covered by a
 * dense diagonal watermark that carries the viewer and the view code. The watermark's tile
 * phase is derived from the view code, so two viewings never carry the same pattern. Never
 * stored; built per request. Any failure throws, so a page is never served unmarked.
 */
final class PageCompositor {

    private PageCompositor() {}

    static byte[] compose(byte[] baseImage, Access access, String identity, String viewCode,
                          String stamp, int maxWidth) throws IOException {
        if (access == Access.WITHHOLD) throw new IllegalArgumentException("A withheld page is never rendered");
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(baseImage));
        if (src == null) throw new IOException("Unreadable page image");
        int w = Math.min(maxWidth, src.getWidth());
        int h = Math.round(src.getHeight() * (w / (float) src.getWidth()));

        BufferedImage img = scale(src, w, h);
        if (access == Access.BLUR) img = blur(img);

        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            tileWatermark(g, w, h, identity + "  ·  " + viewCode, viewCode);
            footer(g, w, h, "Preview only  ·  " + identity + "  ·  " + viewCode + "  ·  " + stamp);
        } finally {
            g.dispose();
        }
        return jpeg(img, 0.72f);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Averages the page into coarse blocks and smooths them back up: shapes and colour blocks
     * survive, words do not. The averaging is explicit; a plain drawImage downscale only samples
     * a few pixels per block, so fine text would alias straight through it.
     */
    static BufferedImage blur(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int tw = Math.max(8, w / 22), th = Math.max(8, h / 22);
        BufferedImage tiny = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        for (int ty = 0; ty < th; ty++) {
            int y0 = ty * h / th, y1 = Math.max(y0 + 1, (ty + 1) * h / th);
            for (int tx = 0; tx < tw; tx++) {
                int x0 = tx * w / tw, x1 = Math.max(x0 + 1, (tx + 1) * w / tw);
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int rgb = img.getRGB(x, y);
                        r += (rgb >> 16) & 0xff;
                        g += (rgb >> 8) & 0xff;
                        b += rgb & 0xff;
                        n++;
                    }
                }
                tiny.setRGB(tx, ty, ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n));
            }
        }
        BufferedImage up = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = up.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(tiny, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return up;
    }

    private static void tileWatermark(Graphics2D g, int w, int h, String text, String viewCode) {
        int size = Math.max(14, w / 34);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        FontMetrics fm = g.getFontMetrics();
        int tileW = fm.stringWidth(text) + size * 3;
        int tileH = size * 5;
        int[] phase = phase(viewCode, tileW, tileH);

        g.rotate(Math.toRadians(-30), w / 2.0, h / 2.0);
        int span = (int) Math.hypot(w, h);
        int row = 0;
        for (int y = -span + phase[1]; y < span * 2; y += tileH, row++) {
            int shift = (row % 2 == 0) ? 0 : tileW / 2;
            for (int x = -span + phase[0] + shift; x < span * 2; x += tileW) {
                // Light then dark, so the mark reads on a dark page and on a pale one.
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f));
                g.setColor(Color.WHITE);
                g.drawString(text, x + 1, y + 1);
                g.setColor(Color.BLACK);
                g.drawString(text, x, y);
            }
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setTransform(new java.awt.geom.AffineTransform());
    }

    /** Tile offsets derived from the view code: the forensic fingerprint of one viewing. */
    static int[] phase(String viewCode, int tileW, int tileH) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(viewCode.getBytes(StandardCharsets.UTF_8));
            int a = ((d[0] & 0xff) << 8) | (d[1] & 0xff);
            int b = ((d[2] & 0xff) << 8) | (d[3] & 0xff);
            return new int[]{a % Math.max(1, tileW), b % Math.max(1, tileH)};
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void footer(Graphics2D g, int w, int h, String text) {
        int band = Math.max(22, h / 40);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.62f));
        g.setColor(Color.BLACK);
        g.fillRect(0, h - band, w, band);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, band / 2)));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, Math.max(8, (w - fm.stringWidth(text)) / 2), h - band / 2 + fm.getAscent() / 2 - 2);
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), p);
            ios.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
