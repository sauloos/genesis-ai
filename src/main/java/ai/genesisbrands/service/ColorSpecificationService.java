package ai.genesisbrands.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Converts hex colour values to CMYK, RGB, and the nearest Pantone Solid Coated name.
 * CMYK conversion is algorithmic; Pantone matching is nearest-neighbour by Euclidean
 * RGB distance over a curated ~100-colour Pantone Solid Coated reference table.
 */
@Service
public class ColorSpecificationService {

    public record CmykValues(int cyan, int magenta, int yellow, int black) {
        @Override public String toString() {
            return "C:%d M:%d Y:%d K:%d".formatted(cyan, magenta, yellow, black);
        }
    }

    public record RgbValues(int red, int green, int blue) {
        @Override public String toString() { return "R:%d G:%d B:%d".formatted(red, green, blue); }
    }

    public record ColorSpec(RgbValues rgb, CmykValues cmyk, String pantone) {}

    private record PantoneEntry(String name, int r, int g, int b) {}

    private static final List<PantoneEntry> PANTONE_SOLID_COATED = List.of(
        // Reds
        new PantoneEntry("Pantone Red C",    238,  34,  36),
        new PantoneEntry("Pantone 485 C",    218,  41,  28),
        new PantoneEntry("Pantone 186 C",    200,  16,  46),
        new PantoneEntry("Pantone 200 C",    187,   8,  56),
        new PantoneEntry("Pantone 1795 C",   217,  58,  52),
        new PantoneEntry("Pantone 193 C",    226,  39,  84),
        new PantoneEntry("Pantone 1655 C",   255,  96,  24),
        // Pinks & Magentas
        new PantoneEntry("Pantone 213 C",    234,  67, 134),
        new PantoneEntry("Pantone 219 C",    224,  37, 120),
        new PantoneEntry("Pantone 226 C",    222,  34, 131),
        new PantoneEntry("Pantone 232 C",    238,  53, 145),
        new PantoneEntry("Pantone 242 C",    169,  40, 110),
        new PantoneEntry("Pantone Magenta C",206,   0, 112),
        new PantoneEntry("Pantone 235 C",    224,  17, 108),
        new PantoneEntry("Pantone 248 C",    197,  17, 131),
        new PantoneEntry("Pantone 253 C",    187,  55, 162),
        // Oranges
        new PantoneEntry("Pantone Orange 021 C", 254, 80,   0),
        new PantoneEntry("Pantone 166 C",    242, 107,  45),
        new PantoneEntry("Pantone 152 C",    235, 129,  27),
        new PantoneEntry("Pantone 144 C",    234, 118,   0),
        new PantoneEntry("Pantone 159 C",    199,  91,  18),
        new PantoneEntry("Pantone 1375 C",   255, 163,   0),
        new PantoneEntry("Pantone 137 C",    255, 182,  41),
        // Yellows
        new PantoneEntry("Pantone Yellow C", 255, 215,   0),
        new PantoneEntry("Pantone 116 C",    255, 200,   0),
        new PantoneEntry("Pantone 012 C",    255, 210,   0),
        new PantoneEntry("Pantone 1235 C",   255, 179,   0),
        new PantoneEntry("Pantone 130 C",    255, 182,   0),
        new PantoneEntry("Pantone 109 C",    255, 205,   0),
        // Yellow-Greens
        new PantoneEntry("Pantone 376 C",    120, 190,  32),
        new PantoneEntry("Pantone 382 C",    176, 210,   0),
        new PantoneEntry("Pantone 389 C",    196, 220,   0),
        new PantoneEntry("Pantone 390 C",    171, 203,   0),
        new PantoneEntry("Pantone 395 C",    217, 220,  23),
        // Greens
        new PantoneEntry("Pantone Green C",    0, 154,  68),
        new PantoneEntry("Pantone 355 C",      0, 163,  75),
        new PantoneEntry("Pantone 361 C",     79, 188,  68),
        new PantoneEntry("Pantone 347 C",      0, 136,  58),
        new PantoneEntry("Pantone 348 C",      0, 122,  61),
        new PantoneEntry("Pantone 364 C",     83, 152,  38),
        new PantoneEntry("Pantone 3415 C",     0, 119,  73),
        new PantoneEntry("Pantone 7482 C",    72, 200, 129),
        // Teals & Cyans
        new PantoneEntry("Pantone 336 C",      0, 136, 103),
        new PantoneEntry("Pantone 329 C",      0, 136, 119),
        new PantoneEntry("Pantone 320 C",      0, 156, 166),
        new PantoneEntry("Pantone 3262 C",     0, 180, 188),
        new PantoneEntry("Pantone 3125 C",     0, 182, 194),
        new PantoneEntry("Pantone 3135 C",     0, 157, 182),
        new PantoneEntry("Pantone 3145 C",     0, 137, 162),
        new PantoneEntry("Pantone 5483 C",    52, 120, 120),
        new PantoneEntry("Pantone 7474 C",    68, 143, 138),
        new PantoneEntry("Pantone Cyan C",     0, 174, 239),
        // Blues
        new PantoneEntry("Pantone 292 C",    104, 172, 221),
        new PantoneEntry("Pantone 279 C",     83, 147, 204),
        new PantoneEntry("Pantone 2718 C",    94, 149, 210),
        new PantoneEntry("Pantone 2727 C",    65, 130, 213),
        new PantoneEntry("Pantone 300 C",      0, 100, 177),
        new PantoneEntry("Pantone 307 C",      0, 104, 163),
        new PantoneEntry("Pantone 286 C",      0,  68, 170),
        new PantoneEntry("Pantone 2748 C",    23,  65, 148),
        new PantoneEntry("Pantone Blue 072 C", 0,  32, 160),
        new PantoneEntry("Pantone Reflex Blue C", 0, 20, 137),
        // Navies
        new PantoneEntry("Pantone 295 C",      0,  45, 114),
        new PantoneEntry("Pantone 654 C",      0,  56, 131),
        new PantoneEntry("Pantone 289 C",     12,  35,  75),
        new PantoneEntry("Pantone 2965 C",     0,  42,  72),
        new PantoneEntry("Pantone 539 C",      6,  35,  65),
        // Purples & Violets
        new PantoneEntry("Pantone 265 C",    175, 108, 213),
        new PantoneEntry("Pantone 2715 C",   168, 100, 218),
        new PantoneEntry("Pantone 2645 C",   195, 132, 231),
        new PantoneEntry("Pantone 526 C",    145,  64, 166),
        new PantoneEntry("Pantone 7671 C",    98,  70, 159),
        new PantoneEntry("Pantone 2617 C",   108,  10, 129),
        new PantoneEntry("Pantone 2603 C",   100,   0, 128),
        new PantoneEntry("Pantone Purple C", 119,   0, 136),
        new PantoneEntry("Pantone Violet C",  66,   0, 133),
        // Browns & Tans
        new PantoneEntry("Pantone 7508 C",   223, 195, 147),
        new PantoneEntry("Pantone 7529 C",   198, 180, 160),
        new PantoneEntry("Pantone 7528 C",   194, 178, 155),
        new PantoneEntry("Pantone 462 C",    167, 130,  74),
        new PantoneEntry("Pantone 469 C",    152,  83,  30),
        new PantoneEntry("Pantone 476 C",    114,  76,  55),
        // Golds
        new PantoneEntry("Pantone 128 C",    248, 200,  49),
        new PantoneEntry("Pantone 124 C",    234, 181,  31),
        new PantoneEntry("Pantone 7561 C",   175, 126,  45),
        new PantoneEntry("Pantone 871 C",    172, 145,  90),
        // Warm Grays
        new PantoneEntry("Pantone Warm Gray 1 C",  215, 205, 196),
        new PantoneEntry("Pantone Warm Gray 3 C",  202, 191, 181),
        new PantoneEntry("Pantone Warm Gray 5 C",  186, 175, 163),
        new PantoneEntry("Pantone Warm Gray 7 C",  165, 153, 140),
        new PantoneEntry("Pantone Warm Gray 9 C",  153, 139, 124),
        new PantoneEntry("Pantone Warm Gray 11 C", 133, 119, 105),
        // Cool Grays
        new PantoneEntry("Pantone Cool Gray 1 C",  215, 215, 215),
        new PantoneEntry("Pantone Cool Gray 3 C",  200, 201, 199),
        new PantoneEntry("Pantone Cool Gray 5 C",  186, 186, 186),
        new PantoneEntry("Pantone Cool Gray 7 C",  167, 168, 166),
        new PantoneEntry("Pantone Cool Gray 9 C",  138, 141, 143),
        new PantoneEntry("Pantone Cool Gray 11 C", 108, 112, 114),
        // Blacks & White
        new PantoneEntry("Pantone Black C",   44,  42,  41),
        new PantoneEntry("Pantone Black 6 C",  0,   0,   0),
        new PantoneEntry("Pantone White",    255, 255, 255)
    );

    public RgbValues hexToRgb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new RgbValues(
            Integer.parseInt(h.substring(0, 2), 16),
            Integer.parseInt(h.substring(2, 4), 16),
            Integer.parseInt(h.substring(4, 6), 16)
        );
    }

    public CmykValues hexToCmyk(String hex) {
        RgbValues rgb = hexToRgb(hex);
        double r = rgb.red()   / 255.0;
        double g = rgb.green() / 255.0;
        double b = rgb.blue()  / 255.0;

        double k = 1.0 - Math.max(r, Math.max(g, b));
        if (k == 1.0) return new CmykValues(0, 0, 0, 100);

        double denom = 1.0 - k;
        int c = (int) Math.round((1.0 - r - k) / denom * 100);
        int m = (int) Math.round((1.0 - g - k) / denom * 100);
        int y = (int) Math.round((1.0 - b - k) / denom * 100);
        int kv = (int) Math.round(k * 100);
        return new CmykValues(c, m, y, kv);
    }

    public String nearestPantone(String hex) {
        RgbValues rgb = hexToRgb(hex);
        PantoneEntry best = null;
        double bestDist = Double.MAX_VALUE;
        for (PantoneEntry entry : PANTONE_SOLID_COATED) {
            double dr = rgb.red()   - entry.r();
            double dg = rgb.green() - entry.g();
            double db = rgb.blue()  - entry.b();
            double dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) { bestDist = dist; best = entry; }
        }
        return best != null ? best.name() : "Pantone Black C";
    }

    public ColorSpec fullSpec(String hex) {
        return new ColorSpec(hexToRgb(hex), hexToCmyk(hex), nearestPantone(hex));
    }
}
