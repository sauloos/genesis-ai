package ai.genesisbrands.agent.logo;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cuts the symbol out of a primary lockup by its pixels, so the symbol is exactly the one in the
 * lockup (an image-model edit redraws it and drifts: other colours, other shapes). It reads the
 * transparent background: in a stacked lockup the symbol is the block above the widest empty row
 * band, standing taller than the single text line below it; in a horizontal lockup it is the leading block of columns
 * that stands taller than the letters after it. When neither holds cleanly it returns empty and
 * the caller falls back to the edit.
 */
final class SymbolCrop {

    private static final int ALPHA_ON = 24;
    private static final int SPECK = 150;   // px² at 1024px; a real symbol detail is far larger
    /** The symbol must stand at least this much taller than the letters beside it. */
    private static final double TALLER_THAN_TEXT = 1.12;
    private static final int MAX_UPSCALE = 4;
    private static final int TARGET_SIDE = 640;

    private SymbolCrop() {}

    static Optional<byte[]> crop(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null || !img.getColorModel().hasAlpha()) return Optional.empty();
            boolean[][] on = mask(img);
            Box all = bounds(on, 0, 0, img.getWidth(), img.getHeight());
            if (all == null) return Optional.empty();
            Cut cut = stacked(on, all);
            if (cut == null) cut = horizontal(on, all);
            if (cut == null) return Optional.empty();
            BufferedImage symbol = extract(img, on, cut);
            return symbol == null ? Optional.empty() : Optional.of(write(square(symbol)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ── segmentation ─────────────────────────────────────────────────────────

    record Box(int x0, int y0, int x1, int y1) {   // x1, y1 exclusive
        int w() { return x1 - x0; }
        int h() { return y1 - y0; }
    }

    /**
     * Where the symbol is: {@code box}, plus, for a horizontal lockup, the letters' row band. A
     * letter whose columns the symbol overhangs (an arrow tip above the first capital) falls
     * inside the box; it is dropped as a separate shape lying wholly in that band, on the box's
     * right-hand side.
     */
    record Cut(Box box, int textTop, int textBottom) {
        static Cut of(Box box) { return new Cut(box, -1, -1); }
        boolean isLetter(Box part) {
            return textTop >= 0 && part.y0 >= textTop - 2 && part.y1 <= textBottom + 2
                && part.x0 >= box.x0 + box.w() / 2;
        }
    }

    private record Run(int from, int to) {          // to exclusive
        int len() { return to - from; }
    }

    /** Opaque pixels, minus specks (connected groups smaller than SPECK pixels) the model left behind. */
    private static boolean[][] mask(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[][] on = new boolean[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                on[y][x] = (img.getRGB(x, y) >>> 24) > ALPHA_ON;
        boolean[][] seen = new boolean[h][w];
        int[] stack = new int[w * h];
        List<Integer> group = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (!on[y][x] || seen[y][x]) continue;
                group.clear();
                int top = 0;
                stack[top++] = y * w + x;
                seen[y][x] = true;
                while (top > 0) {
                    int p = stack[--top];
                    if (group.size() < SPECK) group.add(p);
                    int px = p % w, py = p / w;
                    int[][] next = {{px + 1, py}, {px - 1, py}, {px, py + 1}, {px, py - 1}};
                    for (int[] n : next)
                        if (n[0] >= 0 && n[0] < w && n[1] >= 0 && n[1] < h && on[n[1]][n[0]] && !seen[n[1]][n[0]]) {
                            seen[n[1]][n[0]] = true;
                            stack[top++] = n[1] * w + n[0];
                        }
                }
                if (group.size() < SPECK) for (int p : group) on[p / w][p % w] = false;
            }
        return on;
    }

    private static Box bounds(boolean[][] on, int x0, int y0, int x1, int y1) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                if (on[y][x]) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
        return maxX < 0 ? null : new Box(minX, minY, maxX + 1, maxY + 1);
    }

    /** Runs of occupied rows (byRow) or columns inside the box. */
    private static List<Run> runs(boolean[][] on, Box b, boolean byRow) {
        List<Run> runs = new ArrayList<>();
        int from = -1;
        int end = byRow ? b.y1 : b.x1;
        for (int i = byRow ? b.y0 : b.x0; i < end; i++) {
            boolean any = false;
            for (int j = byRow ? b.x0 : b.y0; j < (byRow ? b.x1 : b.y1) && !any; j++)
                any = byRow ? on[i][j] : on[j][i];
            if (any && from < 0) from = i;
            if (!any && from >= 0) { runs.add(new Run(from, i)); from = -1; }
        }
        if (from >= 0) runs.add(new Run(from, end));
        return runs;
    }

    /** Symbol above, one line of text below, split at the widest empty row band. */
    private static Cut stacked(boolean[][] on, Box all) {
        List<Run> rows = runs(on, all, true);
        if (rows.size() < 2) return null;
        int split = 0, widest = -1;
        for (int i = 0; i + 1 < rows.size(); i++) {
            int gap = rows.get(i + 1).from() - rows.get(i).to();
            if (gap > widest) { widest = gap; split = i; }
        }
        Box top = bounds(on, all.x0, all.y0, all.x1, rows.get(split).to());
        Box bottom = bounds(on, all.x0, rows.get(split + 1).from(), all.x1, all.y1);
        if (top == null || bottom == null) return null;
        boolean textBelow = bottom.w() >= 3 * bottom.h();
        // taller than the text line, so a two-line wordmark is never mistaken for a symbol
        boolean symbolSized = top.h() >= 1.3 * bottom.h();
        return textBelow && symbolSized ? Cut.of(top) : null;
    }

    /** Symbol on the left: the leading column blocks that stand taller than the letters after them. */
    private static Cut horizontal(boolean[][] on, Box all) {
        List<Box> blocks = new ArrayList<>();
        for (Run r : runs(on, all, false)) {
            Box b = bounds(on, r.from(), all.y0, r.to(), all.y1);
            if (b != null) blocks.add(b);
        }
        if (blocks.size() < 3) return null;   // a symbol and at least two letters
        int i = 0;
        // letters' height: the median of everything after the first block
        double textH = median(blocks.subList(1, blocks.size()).stream().mapToInt(Box::h).toArray());
        while (i < blocks.size() && blocks.get(i).h() >= TALLER_THAN_TEXT * textH) i++;
        if (i == 0 || i >= blocks.size() - 1) return null;
        Box symbol = bounds(on, blocks.get(0).x0, all.y0, blocks.get(i - 1).x1, all.y1);
        if (symbol == null) return null;
        boolean notTooWide = symbol.w() <= 1.8 * symbol.h() && symbol.w() <= 0.45 * all.w();
        if (!notTooWide) return null;
        int textTop = Integer.MAX_VALUE, textBottom = -1;
        for (Box letter : blocks.subList(i, blocks.size())) {
            textTop = Math.min(textTop, letter.y0);
            textBottom = Math.max(textBottom, letter.y1);
        }
        return new Cut(symbol, textTop, textBottom);
    }

    private static double median(int[] values) {
        int[] v = values.clone();
        java.util.Arrays.sort(v);
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2.0;
    }

    // ── output ───────────────────────────────────────────────────────────────

    /**
     * The symbol's own pixels: the shapes in the cut's box, minus any letter caught in it, each
     * with a 2px halo so its anti-aliased edge comes along. Null when nothing is left.
     */
    private static BufferedImage extract(BufferedImage img, boolean[][] on, Cut cut) {
        Box b = cut.box();
        int w = b.w(), h = b.h();
        int[] label = new int[w * h];
        List<Boolean> keep = new ArrayList<>();
        keep.add(false);                                  // label 0 = background
        int[] stack = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (!on[b.y0 + y][b.x0 + x] || label[y * w + x] != 0) continue;
                int id = keep.size();
                int minX = x, maxX = x, minY = y, maxY = y, top = 0;
                stack[top++] = y * w + x;
                label[y * w + x] = id;
                while (top > 0) {
                    int p = stack[--top], px = p % w, py = p / w;
                    minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                    int[][] next = {{px + 1, py}, {px - 1, py}, {px, py + 1}, {px, py - 1}};
                    for (int[] n : next)
                        if (n[0] >= 0 && n[0] < w && n[1] >= 0 && n[1] < h
                                && on[b.y0 + n[1]][b.x0 + n[0]] && label[n[1] * w + n[0]] == 0) {
                            label[n[1] * w + n[0]] = id;
                            stack[top++] = n[1] * w + n[0];
                        }
                }
                Box part = new Box(b.x0 + minX, b.y0 + minY, b.x0 + maxX + 1, b.y0 + maxY + 1);
                keep.add(!cut.isLetter(part));
            }
        int halo = 2, minX = w, minY = h, maxX = -1, maxY = -1;
        boolean[] in = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (!keep.get(label[y * w + x])) continue;
                for (int dy = -halo; dy <= halo; dy++)
                    for (int dx = -halo; dx <= halo; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) in[ny * w + nx] = true;
                    }
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        if (maxX < 0) return null;
        BufferedImage out = new BufferedImage(maxX - minX + 1, maxY - minY + 1, BufferedImage.TYPE_INT_ARGB);
        for (int y = minY; y <= maxY; y++)
            for (int x = minX; x <= maxX; x++)
                if (in[y * w + x]) out.setRGB(x - minX, y - minY, img.getRGB(b.x0 + x, b.y0 + y));
        return out;
    }

    /** The symbol centred on a transparent square with a margin of a sixth of the side each way. */
    private static BufferedImage square(BufferedImage symbol) {
        int longest = Math.max(symbol.getWidth(), symbol.getHeight());
        double scale = Math.max(1.0, Math.min(MAX_UPSCALE, (TARGET_SIDE * 2.0 / 3.0) / longest));
        int w = (int) Math.round(symbol.getWidth() * scale);
        int h = (int) Math.round(symbol.getHeight() * scale);
        int side = (int) Math.round(Math.max(w, h) * 1.5);
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(symbol, (side - w) / 2, (side - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] write(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
