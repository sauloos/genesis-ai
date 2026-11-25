package ai.genesisbrands.agent.brandbook;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Makes a raster logo's paper-coloured fills transparent, so the logo survives being whited out.
 *
 * <p>Image models often paint the counters of a reversed lockup (letters cut out of a solid block)
 * in cream or white instead of leaving them transparent. The templates reverse a logo for a dark
 * ground with {@code filter: brightness(0) invert(1)}, which turns every opaque pixel white, so
 * those letters vanish into a white block. A light, near-neutral region that is entirely enclosed
 * by ink is such a fill: it becomes transparent, and the page shows through it on any ground.
 * A light region that reaches the transparent outside is the mark's own ink and is kept.
 */
final class LogoKnockout {

    /** Pixels at or below this alpha count as the transparent outside. */
    static final int ALPHA_FLOOR = 16;
    /** A paper pixel is at least this light (relative luminance)... */
    static final double PAPER_LUMINANCE = 0.85;
    /** ...and at most this saturated (max - min channel, 0..1). */
    static final double PAPER_CHROMA = 0.15;

    private LogoKnockout() {}

    static byte[] apply(byte[] png) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException e) {
            return png;
        }
        if (src == null || !src.getColorModel().hasAlpha()) return png;

        int w = src.getWidth(), h = src.getHeight(), n = w * h;
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        boolean[] paper = new boolean[n];
        for (int i = 0; i < n; i++) paper[i] = isPaper(argb[i]);

        // Flood each paper region; knock it out only if it never touches the outside.
        boolean[] seen = new boolean[n];
        boolean[] knock = new boolean[n];
        int[] queue = new int[n];
        boolean any = false;
        for (int start = 0; start < n; start++) {
            if (!paper[start] || seen[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            boolean outside = false;
            while (head < tail) {
                int p = queue[head++], x = p % w, y = p / w;
                int[] nbs = {x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1};
                for (int q : nbs) {
                    if (q < 0 || alpha(argb[q]) <= ALPHA_FLOOR) { outside = true; continue; }
                    if (paper[q] && !seen[q]) { seen[q] = true; queue[tail++] = q; }
                }
            }
            if (!outside) {
                for (int k = 0; k < tail; k++) knock[queue[k]] = true;
                any = true;
            }
        }
        if (!any) return png;

        int[] out = argb.clone();
        for (int p = 0; p < n; p++) {
            if (knock[p]) { out[p] = 0; continue; }
            // Soften the anti-aliased rim between ink and a knocked-out fill: the lighter the
            // pixel, the more of it was paper.
            if (alpha(argb[p]) > ALPHA_FLOOR && bordersKnockout(knock, p, w, h)) {
                double ink = Math.min(1, Math.max(0, (0.88 - luminance(argb[p])) / 0.68));
                int a = (int) Math.round(alpha(argb[p]) * ink);
                out[p] = (a << 24) | (argb[p] & 0xffffff);
            }
        }
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        dst.setRGB(0, 0, w, h, out, 0, w);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(dst, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            return png;
        }
    }

    private static boolean bordersKnockout(boolean[] knock, int p, int w, int h) {
        int x = p % w, y = p / w;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if ((dx != 0 || dy != 0) && nx >= 0 && ny >= 0 && nx < w && ny < h && knock[ny * w + nx]) return true;
            }
        }
        return false;
    }

    private static boolean isPaper(int px) {
        if (alpha(px) <= ALPHA_FLOOR) return false;
        int r = (px >> 16) & 0xff, g = (px >> 8) & 0xff, b = px & 0xff;
        double chroma = (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))) / 255.0;
        return luminance(px) >= PAPER_LUMINANCE && chroma <= PAPER_CHROMA;
    }

    private static double luminance(int px) {
        int r = (px >> 16) & 0xff, g = (px >> 8) & 0xff, b = px & 0xff;
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
    }

    private static int alpha(int px) {
        return (px >>> 24);
    }
}
