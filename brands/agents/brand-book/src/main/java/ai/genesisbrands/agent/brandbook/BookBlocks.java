package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The brand book's drawn pages: logo misuse demonstrations, clear space, layout grids,
 * applications, stationery and graphic devices. Each is built from the book's own logo,
 * palette, photography and copy, so every page shows this brand rather than a template
 * placeholder (the books of 2026-11-26 repeated the untouched logo under every "don't",
 * drew the same grey grid for every brand and showed three photos as "applications").
 *
 * <p>Everything is HTML in millimetres, styled by {@code _components.css} through the page's
 * own CSS variables, so it sits inside any of the four templates.
 */
final class BookBlocks {

    /** What the blocks are built from. Markup values are ready-to-insert HTML. */
    record Ctx(
        String brandName,
        String tagline,
        String elevatorPitch,
        String mission,
        List<CopyOutput.BrandValue> values,
        String logo,
        String logoWhite,
        String logoOffBrand,
        String symbol,
        String symbolWhite,
        boolean hasSeparateSymbol,
        Map<String, String> photos,
        String primary,
        String panel,
        String ink,
        String secondary,
        String accent,
        String neutral,
        List<VisualIdentityOutput.ColorSwatch> palette,
        String headingFamily,
        String year
    ) {
        String photo(String slot) {
            String p = photos.get(slot);
            return p == null ? "" : p;
        }
    }

    private BookBlocks() {}

    // ── Logo don'ts ───────────────────────────────────────────────────────────

    /** One way of misusing a logo, the words that name it, and how to show it. */
    enum Demo {
        STRETCH("stretch", "condense", "distort", "squash", "squeeze", "skew", "aspect ratio"),
        ROTATE("rotate", "tilt", "angle"),
        CROP("crop", "cut off", "trim"),
        EFFECTS("shadow", "bevel", "gradient", "outline", "glow", "emboss", "effect", "3d"),
        BUSY("busy", "photograph", "photo", "low-contrast", "low contrast", "pattern", "texture"),
        OPACITY("watermark", "opacity", "transparen", "faded", "tint"),
        BORDER("border", "keyline", "plate", "frame", "badge", "container", "box"),
        CROWD("crowd", "clear space", "clearspace", "clutter", "compete", "too close"),
        SEPARATE("separate", "gap", "standalone", "stand-alone", "divider", "split", "rearrange", "reposition", "alone"),
        TYPEFACE("typeface", "font", "rebuild", "retype", "recreate", "re-set", "reset the"),
        SCALE("enlarge", "scale", "oversize", "hero", "too small", "minimum size"),
        RECOLOUR("recolour", "recolor", "colour", "color", "palette", "hue");

        final List<String> words;

        Demo(String... words) { this.words = List.of(words); }

        /** Where in the text this demo is first named, or -1. */
        int firstMention(String lower) {
            int best = -1;
            for (String w : words) {
                int i = lower.indexOf(w);
                if (i >= 0 && (best < 0 || i < best)) best = i;
            }
            return best;
        }
    }

    /** Default captions for the demonstrations a book adds when it has fewer than six rules. */
    private static final Map<Demo, String> DEFAULT_CAPTIONS = Map.of(
        Demo.STRETCH, "Don't stretch or squash the logo — keep its proportions.",
        Demo.ROTATE, "Don't rotate the logo.",
        Demo.EFFECTS, "Don't add shadows, glows or other effects.",
        Demo.BUSY, "Don't place the logo on a busy or low-contrast background.",
        Demo.CROWD, "Don't crowd the logo — keep its clear space free.",
        Demo.RECOLOUR, "Don't recolour the logo outside the palette.");

    /**
     * Picks a demonstration for each rule: the misuse named earliest in its text that no
     * earlier rule has taken. A rule that names none is shown as the logo struck through, so
     * the caption is never illustrated by a different mistake.
     */
    static List<Demo> matchDemos(List<String> donts) {
        List<Demo> out = new ArrayList<>();
        Set<Demo> used = EnumSet.noneOf(Demo.class);
        for (String d : donts) {
            String lower = d.toLowerCase(Locale.ROOT);
            Demo best = null;
            int bestPos = Integer.MAX_VALUE;
            for (Demo demo : Demo.values()) {
                if (used.contains(demo)) continue;
                int p = demo.firstMention(lower);
                if (p >= 0 && p < bestPos) { bestPos = p; best = demo; }
            }
            if (best != null) used.add(best);
            out.add(best);
        }
        return out;
    }

    static String donts(List<String> rules, Ctx c) {
        List<String> captions = new ArrayList<>();
        if (rules != null) for (String r : rules) if (r != null && !r.isBlank()) captions.add(r.strip());
        if (captions.size() > 6) captions = new ArrayList<>(captions.subList(0, 6));
        List<Demo> demos = new ArrayList<>(matchDemos(captions));
        for (Demo d : List.of(Demo.STRETCH, Demo.ROTATE, Demo.EFFECTS, Demo.BUSY, Demo.CROWD, Demo.RECOLOUR)) {
            if (captions.size() >= 6) break;
            if (demos.contains(d)) continue;
            captions.add(DEFAULT_CAPTIONS.get(d));
            demos.add(d);
        }
        StringBuilder sb = new StringBuilder("<div class=\"bb-donts\">");
        for (int i = 0; i < captions.size(); i++) {
            sb.append("<figure class=\"bb-dont\">").append(dontStage(demos.get(i), captions.get(i), c))
              .append("<figcaption>").append(esc(TextFit.fit(captions.get(i), 150))).append("</figcaption></figure>");
        }
        return sb.append("</div>").toString();
    }

    private static String dontStage(Demo demo, String caption, Ctx c) {
        String lower = caption.toLowerCase(Locale.ROOT);
        String stageStyle = "";
        String inner;
        if (demo == null) {
            inner = logoBox(c.logo(), "") + "<div class=\"bb-dont-strike\"></div>";
        } else {
            inner = switch (demo) {
                case STRETCH -> logoBox(c.logo(), lower.contains("condense") && !lower.contains("stretch")
                    ? "transform:scaleX(0.55)" : "transform:scaleX(1.6)");
                case ROTATE -> logoBox(c.logo(), "transform:rotate(-17deg)");
                case CROP -> logoBox(c.logo(), "transform:translateX(42%) scale(1.7)");
                case EFFECTS -> logoBox(c.logo(),
                    "filter:drop-shadow(0.7mm 0.7mm 0 " + offBrand(c.accent()) + ") drop-shadow(1.4mm 1.8mm 1.2mm rgba(0,0,0,0.5))");
                case BUSY -> {
                    // A close crop of a photo reads as busy texture; the whole frame of a stock
                    // shot is often a portrait, and the wordmark ended up across a face.
                    String photo = c.photo("examples-2");
                    if (photo.isEmpty()) photo = c.photo("imagery-1");
                    stageStyle = photo.isEmpty()
                        ? "background:repeating-linear-gradient(45deg," + c.secondary() + " 0 3mm," + c.accent() + " 3mm 6mm," + c.neutral() + " 6mm 9mm)"
                        : "background:url('" + photo + "') 20% 80%/280% auto no-repeat";
                    yield logoBox(c.logo(), "");
                }
                case OPACITY -> logoBox(c.logo(), "opacity:0.18");
                case BORDER -> "<div class=\"bb-dont-plate\">" + logoBox(c.logo(), "") + "</div>";
                case CROWD -> "<div class=\"bb-dont-crowd\"><div class=\"bb-dont-bars\"><i></i><i></i><i></i></div>"
                    + logoBox(c.logo(), "margin:0") + "<div class=\"bb-dont-bars\"><i></i><i></i></div></div>";
                case SEPARATE -> c.hasSeparateSymbol()
                    ? "<div class=\"bb-dont-apart\"><div class=\"bb-dont-sym\">" + c.symbol() + "</div>"
                        + "<div class=\"bb-dont-word\">" + esc(c.brandName()) + "</div></div>"
                    : "<div class=\"bb-dont-apart\"><div class=\"bb-dont-word\">" + esc(firstWord(c.brandName())) + "</div>"
                        + "<div class=\"bb-dont-rule\"></div><div class=\"bb-dont-word\">" + esc(restWords(c.brandName())) + "</div></div>";
                case TYPEFACE -> "<div class=\"bb-dont-serif\">" + esc(c.brandName()) + "</div>";
                case SCALE -> logoBox(c.logo(), "transform:scale(1.9)");
                case RECOLOUR -> logoBox(c.logoOffBrand(), "");
            };
        }
        return "<div class=\"bb-dont-stage\"" + (stageStyle.isEmpty() ? "" : " style=\"" + stageStyle + "\"") + ">"
            + inner + "<span class=\"bb-dont-x\" aria-hidden=\"true\">&#x2715;</span></div>";
    }

    private static String logoBox(String markup, String style) {
        return "<div class=\"bb-dont-logo\"" + (style.isEmpty() ? "" : " style=\"" + style + "\"") + ">" + markup + "</div>";
    }

    /** A colour clearly outside the palette: the accent's hue turned half way round the wheel. */
    static String offBrand(String accentHex) {
        int[] rgb = rgb(accentHex);
        float[] hsb = java.awt.Color.RGBtoHSB(rgb[0], rgb[1], rgb[2], null);
        float hue = (hsb[0] + 0.5f) % 1f;
        java.awt.Color col = java.awt.Color.getHSBColor(hue, 0.75f, 0.85f);
        return String.format("#%02x%02x%02x", col.getRed(), col.getGreen(), col.getBlue());
    }

    // ── Clear space ───────────────────────────────────────────────────────────

    /**
     * The real logo at 30 mm high inside a zone of X = a quarter of its height on every side,
     * with each X marked. The old page drew a generic square labelled LOGO MARK.
     */
    static String clearSpace(Ctx c) {
        String x = "<span class=\"bb-clear-x\">X</span>";
        return "<div class=\"bb-clear\">"
            + "<div class=\"bb-clear-zone\">"
            + "<div class=\"bb-clear-m bb-clear-top\">" + x + "</div>"
            + "<div class=\"bb-clear-m bb-clear-bottom\">" + x + "</div>"
            + "<div class=\"bb-clear-m bb-clear-left\">" + x + "</div>"
            + "<div class=\"bb-clear-m bb-clear-right\">" + x + "</div>"
            + "<div class=\"bb-clear-logo\">" + c.logo() + "</div>"
            + "</div>"
            + "<div class=\"bb-clear-rule\"><b>X</b> = one quarter of the logo's height. Keep this space clear of type, "
            + "images and other marks on every side.</div>"
            + "</div>";
    }

    // ── Grids ─────────────────────────────────────────────────────────────────

    static final double GRID_MARGIN = 16;
    static final double GRID_GUTTER = 4;
    static final int GRID_COLUMNS = 12;

    static double columnWidth(double pageWidth) {
        return (pageWidth - 2 * GRID_MARGIN - (GRID_COLUMNS - 1) * GRID_GUTTER) / GRID_COLUMNS;
    }

    /** An A4 page drawn to scale in millimetres: margins, 12 columns and a sample layout. */
    static String grid(boolean landscape, Ctx c) {
        double w = landscape ? 297 : 210;
        double h = landscape ? 210 : 297;
        double m = GRID_MARGIN, g = GRID_GUTTER, col = columnWidth(w);
        StringBuilder s = new StringBuilder();
        s.append("<svg class=\"bb-grid-sheet\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
         .append(fmt(w)).append(' ').append(fmt(h)).append("\" width=\"").append(fmt(landscape ? 150 : 106)).append("mm\" height=\"")
         .append(fmt(landscape ? 106 : 150)).append("mm\" style=\"font-family:var(--font-body)\">");
        s.append("<defs><clipPath id=\"bbgp").append(landscape ? "l" : "p").append("\">");
        double[] photo = landscape ? new double[]{x(0, col), m + 34, span(6, col), 110} : new double[]{x(0, col), m + 50, span(7, col), 96};
        s.append(rect(photo[0], photo[1], photo[2], photo[3], "#000", 1)).append("</clipPath></defs>");
        s.append(rect(0, 0, w, h, "#ffffff", 1));
        for (int i = 0; i < GRID_COLUMNS; i++) s.append(rect(x(i, col), m, col, h - 2 * m, c.primary(), 0.07));
        // Sample layout
        double headH = landscape ? 26 : 40;
        s.append(rect(m, m, w - 2 * m, headH, c.panel(), 1));
        s.append(rect(m + 6, m + headH - 15, landscape ? 90 : 70, 6, "#ffffff", 0.95));
        s.append(rect(m + 6, m + headH - 7, landscape ? 60 : 44, 2.4, "#ffffff", 0.6));
        String img = c.photo(landscape ? "imagery-3" : "imagery-2");
        if (!img.isEmpty()) {
            s.append("<image href=\"").append(img).append("\" x=\"").append(fmt(photo[0])).append("\" y=\"").append(fmt(photo[1]))
             .append("\" width=\"").append(fmt(photo[2])).append("\" height=\"").append(fmt(photo[3]))
             .append("\" preserveAspectRatio=\"xMidYMid slice\" clip-path=\"url(#bbgp").append(landscape ? "l" : "p").append(")\"/>");
        } else {
            s.append(rect(photo[0], photo[1], photo[2], photo[3], c.secondary(), 1));
        }
        int textCol = landscape ? 6 : 7;
        double tx = x(textCol, col), tw = span(GRID_COLUMNS - textCol, col);
        s.append(rect(tx, photo[1], tw * 0.8, 5, c.ink(), 1));
        s.append(rect(tx, photo[1] + 8, 18, 1.2, c.accent(), 1));
        for (int i = 0; i < (landscape ? 15 : 13); i++) {
            double lw = i % 5 == 4 ? tw * 0.55 : tw;
            s.append(rect(tx, photo[1] + 14 + i * 5.5, lw, 1.8, c.ink(), 0.28));
        }
        if (!landscape) {
            double by = m + 156;
            for (int b = 0; b < 3; b++) {
                double bx = x(b * 4, col), bw = span(4, col);
                s.append(rect(bx, by, bw, 1.2, c.accent(), 1));
                s.append(rect(bx, by + 5, bw * 0.7, 3.5, c.ink(), 0.85));
                for (int i = 0; i < 6; i++) s.append(rect(bx, by + 12 + i * 5, i == 5 ? bw * 0.5 : bw, 1.6, c.ink(), 0.25));
            }
            s.append(rect(m, h - m - 1, w - 2 * m, 0.4, c.ink(), 0.4));
        }
        // Margin outline and measurements
        s.append("<rect x=\"").append(fmt(m)).append("\" y=\"").append(fmt(m)).append("\" width=\"").append(fmt(w - 2 * m))
         .append("\" height=\"").append(fmt(h - 2 * m)).append("\" fill=\"none\" stroke=\"").append(c.accent())
         .append("\" stroke-width=\"0.5\" stroke-dasharray=\"2 1.5\"/>");
        s.append(label(m / 2, m / 2 + 1.5, "16", c.ink()));
        s.append(label(w - m / 2, h - m / 2 + 1.5, "16", c.ink()));
        double gx = x(1, col) - g / 2;
        s.append(label(gx, h - m / 2 + 1.5, "4", c.ink()));
        s.append("</svg>");

        String fmtLabel = landscape ? "A4 landscape · 297 × 210 mm" : "A4 portrait · 210 × 297 mm";
        return "<div class=\"bb-grid " + (landscape ? "bb-grid-landscape" : "bb-grid-portrait") + "\">" + s
            + "<dl class=\"bb-grid-spec\">"
            + spec("Format", fmtLabel)
            + spec("Margins", "16 mm on every edge")
            + spec("Columns", "12, each " + fmt1(col) + " mm")
            + spec("Gutter", "4 mm")
            + spec("Body text", landscape ? "Spans 6 columns" : "Spans 5 columns, about 60 characters a line")
            + spec("Images", "Snap to column edges; bleed off the page or stop at the margin, never in between")
            + "</dl></div>";
    }

    private static double x(int column, double col) { return GRID_MARGIN + column * (col + GRID_GUTTER); }
    private static double span(int columns, double col) { return columns * col + (columns - 1) * GRID_GUTTER; }

    private static String rect(double x, double y, double w, double h, String fill, double opacity) {
        return "<rect x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" width=\"" + fmt(w) + "\" height=\"" + fmt(h) + "\" fill=\"" + fill + "\""
            + (opacity < 1 ? " fill-opacity=\"" + fmt(opacity) + "\"" : "") + "/>";
    }

    private static String label(double x, double y, String text, String fill) {
        return "<text x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" font-size=\"4.2\" text-anchor=\"middle\" fill=\"" + fill + "\">" + text + "</text>";
    }

    private static String spec(String k, String v) {
        return "<div><dt>" + k + "</dt><dd>" + v + "</dd></div>";
    }

    // ── Applications ──────────────────────────────────────────────────────────

    /** A square social feed: six posts from the book's photos, palette and copy. */
    static String social(Ctx c) {
        List<CopyOutput.BrandValue> vals = c.values() == null ? List.of() : c.values();
        String v1 = vals.isEmpty() ? c.tagline() : vals.get(0).name();
        String v1d = vals.isEmpty() ? "" : vals.get(0).description();
        String handle = c.brandName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        StringBuilder sb = new StringBuilder("<div class=\"bb-social\">");
        sb.append("<div class=\"bb-social-head\"><div class=\"bb-social-avatar\" style=\"background:").append(c.panel()).append("\">")
          .append(c.symbolWhite()).append("</div><div><div class=\"bb-social-name\">").append(esc(c.brandName()))
          .append("</div><div class=\"bb-social-handle\">@").append(esc(handle)).append("</div></div></div>");
        sb.append("<div class=\"bb-social-grid\">");
        sb.append(post(c.photo("examples-1"), c.panel(),
            "<div class=\"bb-post-top bb-post-head\" style=\"color:#fff\">" + esc(c.tagline()) + "</div>"
            + "<div class=\"bb-post-mark\">" + c.logoWhite() + "</div>", true));
        sb.append(post("", c.panel(),
            "<div class=\"bb-post-quote\" style=\"color:#fff\">&ldquo;" + esc(TextFit.fit(c.mission(), 95)) + "&rdquo;</div>", false));
        sb.append(post(c.photo("imagery-2"), c.secondary(), "<div class=\"bb-post-corner\">" + c.symbolWhite() + "</div>", true));
        String accentInk = onColour(c.accent(), c.ink());
        sb.append(post("", c.accent(),
            "<div class=\"bb-post-big\" style=\"color:" + accentInk + "\">" + esc(v1) + "</div>"
            + "<div class=\"bb-post-small\" style=\"color:" + accentInk + "\">" + esc(TextFit.fit(v1d, 90)) + "</div>", false));
        sb.append(post(c.photo("examples-3"), c.secondary(),
            "<div class=\"bb-post-band\"><div class=\"bb-post-small\">" + esc(TextFit.fit(c.elevatorPitch(), 80)) + "</div></div>", true));
        sb.append(post("", "color-mix(in srgb, " + c.neutral() + " 70%, #ffffff)",
            "<div class=\"bb-post-logo\">" + c.logo() + "</div>", false));
        sb.append("</div><div class=\"bb-app-note\">Illustrative posts for a square feed, built from this book's photography, palette and copy.</div></div>");
        return sb.toString();
    }

    private static String post(String photo, String fill, String inner, boolean shade) {
        String bg = photo.isEmpty() ? "background:" + fill : "background:" + fill + " url('" + photo + "') center/cover";
        return "<div class=\"bb-post" + (shade && !photo.isEmpty() ? " bb-post-shade" : "") + "\" style=\"" + bg + "\">" + inner + "</div>";
    }

    /** A home page in a browser window: navigation, a photographic hero, and the values. */
    static String website(Ctx c) {
        String cta = onColour(c.accent(), c.ink());
        StringBuilder sb = new StringBuilder("<div class=\"bb-browser\">");
        sb.append("<div class=\"bb-browser-bar\"><i></i><i></i><i></i><div class=\"bb-browser-url\">")
          .append(esc(c.brandName())).append("</div></div>");
        sb.append("<div class=\"bb-site-nav\"><div class=\"bb-site-logo\">").append(c.logo()).append("</div>")
          .append("<div class=\"bb-site-links\"><span>About</span><span>Approach</span><span>Stories</span><span>Contact</span></div>")
          .append("<div class=\"bb-site-cta\" style=\"background:").append(c.accent()).append(";color:").append(cta).append("\">Get in touch</div></div>");
        String hero = c.photo("examples-2");
        sb.append("<div class=\"bb-site-hero\" style=\"background:").append(c.panel())
          .append(hero.isEmpty() ? "" : " url('" + hero + "') center/cover").append("\"><div class=\"bb-site-hero-text\">")
          .append("<div class=\"bb-site-h1\">").append(esc(c.tagline())).append("</div>")
          .append("<div class=\"bb-site-sub\">").append(esc(TextFit.fit(c.elevatorPitch(), 150))).append("</div>")
          .append("<div class=\"bb-site-cta\" style=\"background:").append(c.accent()).append(";color:").append(cta)
          .append(";display:inline-block\">Get in touch</div></div></div>");
        sb.append("<div class=\"bb-site-cols\">");
        List<CopyOutput.BrandValue> vals = c.values() == null ? List.of() : c.values();
        for (int i = 0; i < Math.min(3, vals.size()); i++) {
            sb.append("<div><div class=\"bb-site-rule\" style=\"background:").append(c.accent()).append("\"></div><div class=\"bb-site-h3\">")
              .append(esc(vals.get(i).name())).append("</div><div class=\"bb-site-p\">").append(esc(TextFit.fit(vals.get(i).description(), 110)))
              .append("</div></div>");
        }
        sb.append("</div><div class=\"bb-site-foot\" style=\"background:").append(c.panel()).append("\"><div class=\"bb-site-foot-logo\">")
          .append(c.logoWhite()).append("</div><span>&copy; ").append(c.year()).append(' ').append(esc(c.brandName())).append("</span></div>");
        sb.append("</div><div class=\"bb-app-note\">Home page, desktop width. Headline and values set in the brand typefaces.</div>");
        return sb.toString();
    }

    /** A shop front with a fascia and a projecting sign, plus an A-board and a window decal. */
    static String signage(Ctx c) {
        StringBuilder sb = new StringBuilder("<div class=\"bb-sign\">");
        sb.append("<div class=\"bb-shopfront\" style=\"background:color-mix(in srgb, ").append(c.neutral()).append(" 55%, #ffffff)\">")
          .append("<div class=\"bb-fascia\" style=\"background:").append(c.panel()).append("\"><div class=\"bb-fascia-logo\">").append(c.logoWhite()).append("</div></div>")
          .append("<div class=\"bb-blade\"><div class=\"bb-blade-arm\"></div><div class=\"bb-blade-disc\" style=\"background:").append(c.panel()).append("\">")
          .append(c.symbolWhite()).append("</div></div>")
          .append("<div class=\"bb-front-row\"><div class=\"bb-window\"><div class=\"bb-window-decal\">").append(esc(c.tagline())).append("</div></div>")
          .append("<div class=\"bb-door\" style=\"background:").append(c.panel()).append("\"><div class=\"bb-door-glass\"></div></div>")
          .append("<div class=\"bb-window\"></div></div>")
          .append("<div class=\"bb-pavement\"></div></div>");
        sb.append("<div class=\"bb-sign-row\">");
        sb.append("<figure><div class=\"bb-aboard\" style=\"background:").append(c.panel()).append("\"><div class=\"bb-aboard-sym\">").append(c.symbolWhite())
          .append("</div><div class=\"bb-aboard-text\">").append(esc(c.tagline())).append("</div></div><figcaption>A-board · 600 × 900 mm</figcaption></figure>");
        sb.append("<figure><div class=\"bb-decal\" style=\"border-color:").append(c.ink()).append("\"><div class=\"bb-decal-sym\">").append(c.symbol())
          .append("</div><div class=\"bb-decal-name\" style=\"color:").append(c.ink()).append("\">").append(esc(c.brandName()))
          .append("</div></div><figcaption>Window decal · 300 mm diameter</figcaption></figure>");
        sb.append("<figure><div class=\"bb-wayfind\" style=\"background:").append(c.ink()).append("\"><div class=\"bb-wayfind-logo\">").append(c.logoWhite())
          .append("</div><div class=\"bb-wayfind-arrow\" style=\"color:").append(c.accent()).append("\">&#x2192;</div></div><figcaption>Wayfinding panel · 400 × 200 mm</figcaption></figure>");
        sb.append("</div><div class=\"bb-app-note\">Fascia and projecting sign in the panel colour with the logo reversed out; the symbol carries the small formats.</div></div>");
        return sb.toString();
    }

    // ── Stationery ────────────────────────────────────────────────────────────

    /** Business card front and back at full size, a scaled letterhead and a DL envelope. */
    static String stationery(Ctx c) {
        StringBuilder sb = new StringBuilder("<div class=\"bb-stat\"><div class=\"bb-stat-col\">");
        sb.append("<figure><div class=\"bb-card bb-card-front\" style=\"background:").append(c.panel()).append("\"><div class=\"bb-card-logo\">")
          .append(c.logoWhite()).append("</div></div><figcaption>Business card, front · 85 × 55 mm</figcaption></figure>");
        sb.append("<figure><div class=\"bb-card bb-card-back\"><div class=\"bb-card-mini\">").append(c.logo()).append("</div>")
          .append("<div class=\"bb-card-person\">Firstname Lastname</div><div class=\"bb-card-role\">Job title</div>")
          .append("<div class=\"bb-card-lines\"><span>+00 0000 000 000</span><span>name@yourdomain.com</span><span>yourdomain.com</span></div>")
          .append("<div class=\"bb-card-rule\" style=\"background:").append(c.accent()).append("\"></div></div>")
          .append("<figcaption>Business card, back</figcaption></figure>");
        sb.append("<figure><div class=\"bb-env\"><div class=\"bb-env-logo\">").append(c.logo()).append("</div>")
          .append("<div class=\"bb-env-window\"><i></i><i></i><i></i></div><div class=\"bb-env-rule\" style=\"background:").append(c.accent()).append("\"></div></div>")
          .append("<figcaption>Envelope · DL 220 × 110 mm</figcaption></figure>");
        sb.append("</div><div class=\"bb-stat-col\"><figure><div class=\"bb-lh\"><div class=\"bb-lh-head\"><div class=\"bb-lh-logo\">")
          .append(c.logo()).append("</div><div class=\"bb-lh-addr\"><i></i><i></i><i></i></div></div>")
          .append("<div class=\"bb-lh-date\"></div><div class=\"bb-lh-to\"><i></i><i></i><i></i></div><div class=\"bb-lh-body\">");
        for (int i = 0; i < 16; i++) sb.append("<i").append(i % 6 == 5 ? " class=\"short\"" : "").append("></i>");
        sb.append("</div><div class=\"bb-lh-foot\"><div class=\"bb-lh-foot-rule\" style=\"background:").append(c.accent())
          .append("\"></div><i></i></div></div><figcaption>Letterhead · A4 210 × 297 mm</figcaption></figure></div></div>");
        return sb.toString();
    }

    // ── Graphic devices ───────────────────────────────────────────────────────

    private static final Pattern WEIGHT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(px|pt)\\b");

    /** The stroke weight a description names, in CSS px, clamped to what prints well. */
    static double strokeWeight(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher m = WEIGHT.matcher(lower);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1));
            if ("pt".equals(m.group(2))) v = v * 96 / 72;
            return Math.max(0.5, Math.min(8, v));
        }
        if (lower.contains("hairline")) return 0.5;
        if (lower.contains("thick") || lower.contains("heavy") || lower.contains("bold")) return 4;
        return 1;
    }

    /** The palette colours a description names, in the order it names them. */
    static List<String> coloursNamed(String text, List<VisualIdentityOutput.ColorSwatch> palette) {
        record Alias(String name, String hex) {}
        List<Alias> aliases = new ArrayList<>();
        Set<String> fullNames = new java.util.HashSet<>();
        if (palette != null) {
            for (var s : palette) if (s.name() != null) fullNames.add(norm(s.name()));
            for (var s : palette) {
                if (s.name() == null || s.hex() == null) continue;
                String n = norm(s.name());
                aliases.add(new Alias(n, s.hex()));
                String[] words = n.split(" ");
                if (words.length >= 3) {
                    String shorter = String.join(" ", java.util.Arrays.copyOf(words, words.length - 1));
                    if (!fullNames.contains(shorter)) aliases.add(new Alias(shorter, s.hex()));
                }
            }
        }
        aliases.sort(Comparator.comparingInt((Alias a) -> a.name().length()).reversed());
        String t = norm(text);
        boolean[] taken = new boolean[t.length()];
        List<int[]> hits = new ArrayList<>();
        List<String> hexes = new ArrayList<>();
        for (Alias a : aliases) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(a.name()) + "\\b").matcher(t);
            while (m.find()) {
                boolean free = true;
                for (int i = m.start(); i < m.end(); i++) if (taken[i]) { free = false; break; }
                if (!free) continue;
                for (int i = m.start(); i < m.end(); i++) taken[i] = true;
                hits.add(new int[]{m.start(), hexes.size()});
                hexes.add(a.hex());
            }
        }
        hits.sort(Comparator.comparingInt(h -> h[0]));
        List<String> out = new ArrayList<>();
        for (int[] h : hits) out.add(hexes.get(h[1]));
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('-', ' ').replaceAll("\\s+", " ").strip();
    }

    static String strokes(List<String> items, Ctx c) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<div class=\"bb-devices\">");
        for (String item : items) {
            String lower = item.toLowerCase(Locale.ROOT);
            List<String> cols = coloursNamed(item, c.palette());
            String colour = cols.isEmpty() ? c.ink() : cols.get(0);
            String sample;
            if (lower.contains("crop mark") || lower.contains("registration") || lower.contains("tick")) {
                String b = "border-color:" + colour;
                sample = "<div class=\"bb-ticks\"><i style=\"" + b + "\"></i><i style=\"" + b + "\"></i><i style=\"" + b + "\"></i><i style=\"" + b + "\"></i></div>";
            } else {
                String style = lower.contains("dotted") ? "dotted" : lower.contains("dashed") || lower.contains("dash ") ? "dashed"
                    : lower.contains("double") ? "double" : "solid";
                double w = strokeWeight(item);
                if ("double".equals(style)) w = Math.max(3, w);
                sample = "<div class=\"bb-stroke\" style=\"border-top:" + fmt(w) + "px " + style + " " + colour + "\"></div>";
            }
            sb.append("<div class=\"bb-device\"><div class=\"bb-device-sample\">").append(sample).append("</div>")
              .append("<div class=\"bb-device-cap\">").append(esc(TextFit.fit(item, 170))).append("</div></div>");
        }
        return sb.append("</div>").toString();
    }

    static String boxes(List<String> items, Ctx c) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<div class=\"bb-devices\">");
        String sampleText = esc(c.brandName().toUpperCase(Locale.ROOT));
        for (String item : items) {
            String lower = item.toLowerCase(Locale.ROOT);
            List<String> cols = coloursNamed(item, c.palette());
            boolean filled = !lower.contains("no fill")
                && (lower.contains("fill") || lower.contains("block") || lower.contains("flat colour") || lower.contains("flat color"));
            String style;
            if (filled) {
                String fill = cols.isEmpty() ? c.panel() : cols.get(0);
                String text = cols.size() > 1 ? cols.get(1) : onColour(fill, c.ink());
                style = "background:" + fill + ";color:" + text + ";border:0";
            } else {
                String stroke = cols.isEmpty() ? c.ink() : cols.get(0);
                style = "background:transparent;color:" + c.ink() + ";border:" + fmt(strokeWeight(item)) + "px solid " + stroke;
            }
            boolean rounded = lower.contains("rounded") && !lower.contains("0 border-radius") && !lower.contains("no rounded");
            if (rounded) style += ";border-radius:2mm";
            sb.append("<div class=\"bb-device\"><div class=\"bb-device-sample\"><div class=\"bb-box\" style=\"").append(style).append("\">")
              .append(sampleText).append("</div></div><div class=\"bb-device-cap\">").append(esc(TextFit.fit(item, 170))).append("</div></div>");
        }
        return sb.append("</div>").toString();
    }

    // ── Tone of voice ─────────────────────────────────────────────────────────

    static String tone(CopyOutput.ToneGuide guide) {
        if (guide == null) return "";
        StringBuilder sb = new StringBuilder("<div class=\"bb-tone\">");
        if (guide.principles() != null && !guide.principles().isEmpty()) {
            sb.append("<div class=\"bb-kicker\">Voice principles</div><ol class=\"bb-tone-principles\">");
            for (String p : guide.principles()) sb.append("<li>").append(esc(p)).append("</li>");
            sb.append("</ol>");
        }
        if (guide.examples() != null && !guide.examples().isEmpty()) {
            sb.append("<div class=\"bb-kicker\">In practice</div><div class=\"bb-tone-pairs\">");
            for (CopyOutput.ToneExample e : guide.examples()) {
                sb.append("<div class=\"bb-tone-pair\">");
                if (e.principle() != null && !e.principle().isBlank()) {
                    sb.append("<div class=\"bb-tone-for\">").append(esc(e.principle())).append("</div>");
                }
                sb.append("<div class=\"bb-tone-do\"><span>Say</span>").append(esc(e.doThis())).append("</div>")
                  .append("<div class=\"bb-tone-dont\"><span>Not</span>").append(esc(e.notThis())).append("</div></div>");
            }
            sb.append("</div>");
        }
        return sb.append("</div>").toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** White on dark fills, the ink colour on light ones (WCAG luminance, threshold at 3:1 to white). */
    static String onColour(String fillHex, String inkHex) {
        int[] c = rgb(fillHex);
        double[] lin = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = c[i] / 255.0;
            lin[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        double lum = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
        return 1.05 / (lum + 0.05) >= 3.0 ? "#ffffff" : inkHex;
    }

    static int[] rgb(String hex) {
        String h = hex != null && hex.startsWith("#") ? hex.substring(1) : hex;
        if (h == null || h.length() != 6) return new int[]{128, 128, 128};
        try {
            return new int[]{Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16)};
        } catch (NumberFormatException e) {
            return new int[]{128, 128, 128};
        }
    }

    private static String firstWord(String s) {
        int sp = s.indexOf(' ');
        return sp > 0 ? s.substring(0, sp) : s;
    }

    private static String restWords(String s) {
        int sp = s.indexOf(' ');
        return sp > 0 ? s.substring(sp + 1) : "";
    }

    static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
