package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.playbook.PlaybookOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;
import ai.genesisbrands.service.BlobStorageService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Playwright/Chromium-based PDF renderer for the brand book. Loads a fixed
 * HTML/CSS template from the classpath, substitutes {{PLACEHOLDER}} tokens with
 * real brand data and base64-encoded photos/logos, then renders to A4 PDF via
 * headless Chromium — giving full CSS3 support (grid, flexbox, clip-path,
 * CSS custom properties, Google Fonts) that openhtmltopdf could not provide.
 *
 * Playwright browsers must be installed separately from the app:
 *   Local dev: ./gradlew playwright-install (or run with browser already in PATH)
 *   Docker:    RUN java -cp app.jar com.microsoft.playwright.CLI install chromium
 *
 * Thread-safety: a single Browser instance is shared; each render call opens its
 * own BrowserContext and Page and closes them in a finally block.
 */
@Service
public class BrandBookTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(BrandBookTemplateRenderer.class);
    private static final String TEMPLATE_PATH = "templates/brand-book/template.html";
    private static final String ASSET_URL_PREFIX = "/api/assets/";

    private final BlobStorageService blobStorageService;

    private Playwright playwright;
    private Browser browser;
    private String templateHtml;

    public BrandBookTemplateRenderer(BlobStorageService blobStorageService) {
        this.blobStorageService = blobStorageService;
    }

    @PostConstruct
    void init() throws IOException {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        templateHtml = new ClassPathResource(TEMPLATE_PATH).getContentAsString(StandardCharsets.UTF_8);
        log.info("BrandBookTemplateRenderer: Playwright Chromium browser started");
    }

    @PreDestroy
    void destroy() {
        if (browser != null) try { browser.close(); } catch (Exception ignored) {}
        if (playwright != null) try { playwright.close(); } catch (Exception ignored) {}
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] render(BrandBookInput input, BrandBookOutput output) {
        String html = buildHtml(input, output);
        BrowserContext ctx = browser.newContext();
        try {
            Page page = ctx.newPage();
            page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            return page.pdf(new Page.PdfOptions().setFormat("A4").setPrintBackground(true));
        } finally {
            ctx.close();
        }
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private String buildHtml(BrandBookInput input, BrandBookOutput output) {
        DirectionBrief brief = input.brief();
        DirectionBrief.BrandContext brand = brief.brand();
        CopyOutput copy = input.copy();
        VisualIdentityOutput visual = input.visualIdentity();
        LogoOutput logo = input.logo();

        // Colors
        String primaryHex   = findSwatchHex(visual.colorPalette(), "primary",   0);
        String secondaryHex = findSwatchHex(visual.colorPalette(), "secondary", 1);
        String accentHex    = findSwatchHex(visual.colorPalette(), "accent",    2);
        String neutralHex   = findSwatchHex(visual.colorPalette(), "neutral",   3);
        String primaryDark  = darken(primaryHex, 0.35);
        String primaryLight = lighten(primaryHex, 0.75);

        // Typography
        String headlineFont    = visual.typography().headlineFont();
        String bodyFont        = visual.typography().bodyFont();
        String headlineFontUrl = headlineFont.replace(" ", "+");
        String bodyFontUrl     = bodyFont.replace(" ", "+");

        // Logo markup (SVG inline or DALLE base64 img)
        String logoMarkup = buildLogoMarkup(logo);

        // Photo data URIs (empty string = CSS background-color fallback)
        String photoCover   = fetchPhotoDataUri(brief.engagementId(), "cover");
        String photoMission = fetchPhotoDataUri(brief.engagementId(), "mission");

        // HTML snippets
        String swatchHtml       = buildSwatchHtml(visual.colorPalette());
        String visionHtml       = buildVisionHtml(copy.vision());
        String valuesHtml       = buildValuesHtml(copy.values());
        String vocabularyHtml   = buildVocabHtml(copy.vocabulary());
        String gradientHtml     = buildGradientHtml(visual.gradients());
        String dontsHtml        = buildDontsHtml(output.logoDonts(), logoMarkup);
        String contentsHtml     = buildContentsHtml();
        String toneGuideHtml    = buildToneGuideHtml(copy.toneGuide());
        String clearanceSvg     = buildClearanceSvg(primaryHex);
        String strokeHtml       = buildListHtml(visual.graphicDevices() != null ? visual.graphicDevices().strokeVariants() : List.of());
        String boxHtml          = buildListHtml(visual.graphicDevices() != null ? visual.graphicDevices().boxVariants() : List.of());
        String iconUsageNote    = visual.graphicDevices() != null && visual.graphicDevices().iconUsageNote() != null
                                  ? visual.graphicDevices().iconUsageNote() : "";

        String missionTrunc     = truncate(copy.missionStatement(), 90);
        String brandStorySnip   = firstSentence(copy.brandStory());
        String year             = String.valueOf(Year.now().getValue());

        // Build substitution map (order matters — apply longest/most-specific keys first)
        Map<String, String> subs = new LinkedHashMap<>();
        subs.put("{{HEADLINE_FONT_URL}}", headlineFontUrl);
        subs.put("{{BODY_FONT_URL}}",     bodyFontUrl);
        subs.put("{{HEADLINE_FONT}}",     esc(headlineFont));
        subs.put("{{BODY_FONT}}",         esc(bodyFont));
        subs.put("{{PRIMARY_HEX}}",       primaryHex);
        subs.put("{{PRIMARY_DARK_HEX}}", primaryDark);
        subs.put("{{PRIMARY_LIGHT_HEX}}", primaryLight);
        subs.put("{{SECONDARY_HEX}}",    secondaryHex);
        subs.put("{{ACCENT_HEX}}",       accentHex);
        subs.put("{{NEUTRAL_HEX}}",      neutralHex);
        subs.put("{{BRAND_NAME_UPPER}}", esc(brand.name().toUpperCase()));
        subs.put("{{BRAND_NAME}}",       esc(brand.name()));
        subs.put("{{TAGLINE}}",          esc(copy.tagline()));
        subs.put("{{DIRECTION}}",        brief.direction().name());
        subs.put("{{YEAR}}",             year);
        subs.put("{{ELEVATOR_PITCH}}",   esc(copy.elevatorPitch()));
        subs.put("{{WELCOME_LETTER}}",   esc(firstParagraph(output.welcomeLetterOpening() != null
                                             ? output.welcomeLetterOpening() : output.welcomeNote())));
        subs.put("{{HOW_TO_USE}}",       esc(output.howToUseThisGuide()));
        subs.put("{{CONTENTS_HTML}}",    contentsHtml);
        subs.put("{{MISSION_STATEMENT}}", esc(copy.missionStatement()));
        subs.put("{{MISSION_STATEMENT_TRUNCATED}}", esc(missionTrunc));
        subs.put("{{BRAND_STORY_SNIPPET}}", esc(brandStorySnip));
        subs.put("{{VISION_PILLARS_HTML}}", visionHtml);
        subs.put("{{VALUES_HTML}}",      valuesHtml);
        subs.put("{{LOGO_WHITEOUT}}",    logoMarkup);
        subs.put("{{LOGO_MONO}}",        logoMarkup);
        subs.put("{{LOGO_PRIMARY}}",     logoMarkup);
        subs.put("{{LOGO_CLEARANCE_SVG}}", clearanceSvg);
        subs.put("{{LOGO_USAGE}}",       esc(output.logoUsageGuidelines()));
        subs.put("{{LOGO_DONTS_HTML}}", dontsHtml);
        subs.put("{{SWATCH_SECTION_HTML}}", swatchHtml);
        subs.put("{{GRADIENT_SECTION_HTML}}", gradientHtml);
        subs.put("{{STROKE_VARIANTS_HTML}}", strokeHtml);
        subs.put("{{BOX_VARIANTS_HTML}}",    boxHtml);
        subs.put("{{ICON_USAGE_NOTE}}",  esc(iconUsageNote));
        subs.put("{{IMAGERY_GUIDANCE}}", esc(output.imageryGuidance()));
        subs.put("{{TYPEFACE_RATIONALE}}", esc(output.typefaceRationale()));
        subs.put("{{TYPOGRAPHY_USAGE}}", esc(output.typographyUsageGuidelines()));
        subs.put("{{COLOR_USAGE}}",      esc(output.colorUsageGuidelines()));
        subs.put("{{VOCABULARY_HTML}}",  vocabularyHtml);
        subs.put("{{TONE_GUIDE_HTML}}", toneGuideHtml);
        subs.put("{{CLOSING_NOTE}}",     esc(output.closingNote()));
        subs.put("{{PHOTO_COVER_DATA}}", photoCover);
        subs.put("{{PHOTO_MISSION_DATA}}", photoMission);
        for (int i = 1; i <= 5; i++) {
            subs.put("{{PHOTO_IMAGERY_" + i + "_DATA}}", fetchPhotoDataUri(brief.engagementId(), "imagery-" + i));
        }
        for (int i = 1; i <= 3; i++) {
            subs.put("{{PHOTO_EXAMPLES_" + i + "_DATA}}", fetchPhotoDataUri(brief.engagementId(), "examples-" + i));
        }

        String result = templateHtml;
        for (Map.Entry<String, String> e : subs.entrySet()) {
            result = result.replace(e.getKey(), e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    // ── HTML snippet builders ─────────────────────────────────────────────────

    private String buildSwatchHtml(List<VisualIdentityOutput.ColorSwatch> palette) {
        if (palette == null || palette.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (VisualIdentityOutput.ColorSwatch s : palette) {
            sb.append("<div class=\"swatch-card\">");
            sb.append("<div class=\"swatch-color-block\" style=\"background:").append(s.hex()).append(";\"></div>");
            sb.append("<div class=\"swatch-specs\">");
            sb.append("<div class=\"swatch-spec-name\">").append(esc(s.name())).append("</div>");
            sb.append("<div class=\"swatch-spec-row\">");
            sb.append("<span class=\"swatch-spec-pill\"><strong>HEX</strong> ").append(s.hex()).append("</span>");
            if (s.rgb() != null) {
                sb.append("<span class=\"swatch-spec-pill\"><strong>RGB</strong> ")
                  .append(s.rgb().red()).append(" ").append(s.rgb().green()).append(" ").append(s.rgb().blue())
                  .append("</span>");
            }
            if (s.cmyk() != null) {
                sb.append("<span class=\"swatch-spec-pill\"><strong>CMYK</strong> ")
                  .append(s.cmyk().cyan()).append(" ").append(s.cmyk().magenta()).append(" ")
                  .append(s.cmyk().yellow()).append(" ").append(s.cmyk().black())
                  .append("</span>");
            }
            if (s.pantone() != null) {
                sb.append("<span class=\"swatch-spec-pill\"><strong>PANTONE</strong> ").append(esc(s.pantone())).append("</span>");
            }
            sb.append("</div>");
            sb.append("<div style=\"font-size:7pt;color:var(--text-mid);margin-top:2mm;\">").append(esc(s.role())).append("</div>");
            sb.append("</div></div>");
        }
        return sb.toString();
    }

    private String buildVisionHtml(CopyOutput.VisionPillars vision) {
        if (vision == null) return "";
        StringBuilder sb = new StringBuilder();
        addPillar(sb, "Growth",       vision.growth());
        addPillar(sb, "Environment",  vision.environment());
        addPillar(sb, "Philanthropy", vision.philanthropy());
        addPillar(sb, "Fame",         vision.fame());
        addPillar(sb, "Productivity", vision.productivity());
        addPillar(sb, "Location",     vision.location());
        return sb.toString();
    }

    private void addPillar(StringBuilder sb, String label, String text) {
        if (text == null || text.isBlank()) return;
        sb.append("<div class=\"vision-pillar\">")
          .append("<div class=\"vision-pillar-label\">").append(label).append("</div>")
          .append("<div class=\"vision-pillar-text\">").append(esc(text)).append("</div>")
          .append("</div>");
    }

    private String buildValuesHtml(List<CopyOutput.BrandValue> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CopyOutput.BrandValue v : values) {
            String letter = v.name() != null && !v.name().isBlank()
                ? v.name().substring(0, 1).toUpperCase() : "•";
            sb.append("<div class=\"value-card\">")
              .append("<div class=\"value-icon\"><span>").append(letter).append("</span></div>")
              .append("<div class=\"value-name\">").append(esc(v.name())).append("</div>")
              .append("<div class=\"value-desc\">").append(esc(v.description())).append("</div>")
              .append("</div>");
        }
        return sb.toString();
    }

    private String buildVocabHtml(List<CopyOutput.VocabularyEntry> vocabulary) {
        if (vocabulary == null || vocabulary.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CopyOutput.VocabularyEntry e : vocabulary) {
            sb.append("<div class=\"vocab-item\">")
              .append("<div class=\"vocab-word\">").append(esc(e.word())).append("</div>")
              .append("<div class=\"vocab-meaning\">").append(esc(e.meaning())).append("</div>")
              .append("</div>");
        }
        return sb.toString();
    }

    private String buildGradientHtml(List<VisualIdentityOutput.GradientPair> gradients) {
        if (gradients == null || gradients.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (VisualIdentityOutput.GradientPair g : gradients) {
            sb.append("<div class=\"gradient-bar-wrap\">")
              .append("<div class=\"gradient-bar\" style=\"background:linear-gradient(135deg,")
              .append(g.colorA()).append(" 0%,").append(g.colorB()).append(" 100%);\"></div>")
              .append("<div class=\"gradient-meta\">")
              .append("<div class=\"gradient-name\">").append(esc(g.name())).append("</div>")
              .append("<div class=\"gradient-usage\">").append(esc(g.usage())).append("</div>")
              .append("</div></div>");
        }
        return sb.toString();
    }

    private String buildDontsHtml(List<String> donts, String logoMarkup) {
        if (donts == null || donts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String dont : donts) {
            sb.append("<div class=\"dont-card\">")
              .append("<div class=\"dont-mark\">").append(logoMarkup).append("</div>")
              .append("<div class=\"dont-label\">").append(esc(dont)).append("</div>")
              .append("</div>");
        }
        return sb.toString();
    }

    private String buildContentsHtml() {
        String[][] items = {
            {"1", "Brand Statement"}, {"2", "Mission"}, {"3", "Vision"}, {"4", "Values"},
            {"5", "Logo System"}, {"6", "Brand Colours"}, {"7", "Gradients"},
            {"8", "Graphic Devices"}, {"9", "Photography"}, {"10", "Typography"},
            {"11", "Layout &amp; Grids"}, {"12", "Applications"}, {"13", "Stationery"},
            {"14", "Brand Language"}, {"15", "Tone of Voice"},
        };
        StringBuilder sb = new StringBuilder();
        for (String[] item : items) {
            sb.append("<div class=\"contents-item\">")
              .append("<div class=\"contents-num\">").append(item[0]).append("</div>")
              .append("<div class=\"contents-name\">").append(item[1]).append("</div>")
              .append("</div>");
        }
        return sb.toString();
    }

    private String buildToneGuideHtml(CopyOutput.ToneGuide toneGuide) {
        if (toneGuide == null) return "";
        StringBuilder sb = new StringBuilder();
        if (toneGuide.principles() != null && !toneGuide.principles().isEmpty()) {
            sb.append("<div class=\"brand-name-label\" style=\"margin-bottom:4mm;\">Voice Principles</div>");
            for (String p : toneGuide.principles()) {
                sb.append("<div style=\"font-family:var(--font-body);font-size:8.5pt;padding:3mm 4mm;")
                  .append("border-left:2px solid var(--primary);margin-bottom:2.5mm;background:var(--off-white);\">")
                  .append(esc(p)).append("</div>");
            }
        }
        if (toneGuide.examples() != null && !toneGuide.examples().isEmpty()) {
            sb.append("<div style=\"margin-top:5mm;\">")
              .append("<div class=\"brand-name-label\" style=\"margin-bottom:4mm;\">Tone in Practice</div>");
            for (CopyOutput.ToneExample e : toneGuide.examples()) {
                sb.append("<div style=\"display:grid;grid-template-columns:1fr 1fr;gap:3mm;margin-bottom:3mm;\">")
                  .append("<div style=\"background:#edf7f2;padding:3mm 4mm;border-radius:1mm;\">")
                  .append("<div style=\"font-size:6pt;font-weight:700;letter-spacing:0.1em;color:#2a7a50;margin-bottom:1mm;\">DO</div>")
                  .append("<div style=\"font-size:7.5pt;\">").append(esc(e.doThis())).append("</div>")
                  .append("</div>")
                  .append("<div style=\"background:#fdf0f0;padding:3mm 4mm;border-radius:1mm;\">")
                  .append("<div style=\"font-size:6pt;font-weight:700;letter-spacing:0.1em;color:#aa2222;margin-bottom:1mm;\">NOT THIS</div>")
                  .append("<div style=\"font-size:7.5pt;\">").append(esc(e.notThis())).append("</div>")
                  .append("</div></div>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    private String buildListHtml(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append("<div class=\"stroke-item\">").append(esc(item)).append("</div>");
        }
        return sb.toString();
    }

    private String buildClearanceSvg(String primaryHex) {
        return "<svg viewBox=\"0 0 200 200\" xmlns=\"http://www.w3.org/2000/svg\">"
            + "<defs><marker id=\"arr\" markerWidth=\"6\" markerHeight=\"6\" refX=\"6\" refY=\"3\" orient=\"auto\">"
            + "<path d=\"M0,0 L6,3 L0,6\" fill=\"none\" stroke=\"" + primaryHex + "\" stroke-width=\"1\"/>"
            + "</marker></defs>"
            + "<rect x=\"15\" y=\"15\" width=\"170\" height=\"170\" fill=\"" + primaryHex + "\" fill-opacity=\"0.06\" "
            + "stroke=\"" + primaryHex + "\" stroke-width=\"0.6\" stroke-dasharray=\"4,3\"/>"
            + "<rect x=\"50\" y=\"50\" width=\"100\" height=\"100\" fill=\"white\" stroke=\"" + primaryHex + "\" stroke-width=\"1\" rx=\"2\"/>"
            + "<line x1=\"15\" y1=\"33\" x2=\"50\" y2=\"33\" stroke=\"" + primaryHex + "\" stroke-width=\"0.8\" marker-end=\"url(#arr)\" opacity=\"0.7\"/>"
            + "<line x1=\"185\" y1=\"33\" x2=\"150\" y2=\"33\" stroke=\"" + primaryHex + "\" stroke-width=\"0.8\" marker-start=\"url(#arr)\" opacity=\"0.7\"/>"
            + "<text x=\"34\" y=\"11\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"9\" fill=\"" + primaryHex + "\" font-weight=\"700\">X</text>"
            + "<text x=\"165\" y=\"11\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"9\" fill=\"" + primaryHex + "\" font-weight=\"700\">X</text>"
            + "<line x1=\"33\" y1=\"15\" x2=\"33\" y2=\"50\" stroke=\"" + primaryHex + "\" stroke-width=\"0.8\" marker-end=\"url(#arr)\" opacity=\"0.7\"/>"
            + "<line x1=\"33\" y1=\"185\" x2=\"33\" y2=\"150\" stroke=\"" + primaryHex + "\" stroke-width=\"0.8\" marker-start=\"url(#arr)\" opacity=\"0.7\"/>"
            + "<text x=\"9\" y=\"100\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"9\" fill=\"" + primaryHex + "\" font-weight=\"700\">X</text>"
            + "<text x=\"100\" y=\"170\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"6.5\" fill=\"" + primaryHex + "\" opacity=\"0.7\">LOGO MARK</text>"
            + "<text x=\"100\" y=\"192\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"6\" fill=\"" + primaryHex + "\" opacity=\"0.55\">"
            + "Minimum clear space: X = logo height &#215; 0.25</text>"
            + "</svg>";
    }

    // ── Asset helpers ─────────────────────────────────────────────────────────

    private String buildLogoMarkup(LogoOutput logo) {
        if (logo.method() == LogoOutput.Method.SVG_CONCEPT && logo.svgMarkup() != null) {
            String svg = logo.svgMarkup().strip();
            if (svg.startsWith("<?xml")) {
                int end = svg.indexOf("?>") + 2;
                svg = svg.substring(end).strip();
            }
            return svg;
        }
        if (logo.method() == LogoOutput.Method.DALLE && logo.imageUrl() != null) {
            String blobPath = logo.imageUrl().startsWith(ASSET_URL_PREFIX)
                ? logo.imageUrl().substring(ASSET_URL_PREFIX.length())
                : logo.imageUrl();
            try {
                byte[] bytes = blobStorageService.download(blobPath);
                return "<img src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
                    + "\" style=\"max-width:100%;max-height:100%;object-fit:contain;\">";
            } catch (Exception e) {
                log.warn("Cannot load DALLE logo for brand book PDF: {}", e.getMessage());
            }
        }
        return "";
    }

    private String fetchPhotoDataUri(String engagementId, String slot) {
        String blobPath = "assets/photos/%s/%s.jpg".formatted(engagementId, slot);
        try {
            byte[] bytes = blobStorageService.download(blobPath);
            if (bytes == null || bytes.length == 0) return "";
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.debug("Photo unavailable for slot '{}' engagementId='{}': {}", slot, engagementId, e.getMessage());
            return "";
        }
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    private String findSwatchHex(List<VisualIdentityOutput.ColorSwatch> palette, String roleKeyword, int fallbackIndex) {
        if (palette == null || palette.isEmpty()) return "#1a1a1a";
        for (VisualIdentityOutput.ColorSwatch s : palette) {
            if (s.role() != null && s.role().toLowerCase().contains(roleKeyword)) return s.hex();
        }
        for (VisualIdentityOutput.ColorSwatch s : palette) {
            if (s.name() != null && s.name().toLowerCase().contains(roleKeyword)) return s.hex();
        }
        return fallbackIndex < palette.size() ? palette.get(fallbackIndex).hex() : palette.get(0).hex();
    }

    private static int[] parseHex(String hex) {
        String h = hex != null && hex.startsWith("#") ? hex.substring(1) : (hex != null ? hex : "808080");
        if (h.length() != 6) return new int[]{128, 128, 128};
        try {
            return new int[]{
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16)
            };
        } catch (NumberFormatException e) {
            return new int[]{128, 128, 128};
        }
    }

    private static String darken(String hex, double factor) {
        int[] rgb = parseHex(hex);
        return String.format("#%02x%02x%02x",
            Math.max(0, (int)(rgb[0] * (1 - factor))),
            Math.max(0, (int)(rgb[1] * (1 - factor))),
            Math.max(0, (int)(rgb[2] * (1 - factor))));
    }

    private static String lighten(String hex, double factor) {
        int[] rgb = parseHex(hex);
        return String.format("#%02x%02x%02x",
            Math.min(255, (int)(rgb[0] + (255 - rgb[0]) * factor)),
            Math.min(255, (int)(rgb[1] + (255 - rgb[1]) * factor)),
            Math.min(255, (int)(rgb[2] + (255 - rgb[2]) * factor)));
    }

    // ── String helpers ────────────────────────────────────────────────────────

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        int dot = s.lastIndexOf('.', maxLen);
        return dot > 0 ? s.substring(0, dot + 1) : s.substring(0, maxLen) + "…";
    }

    private static String firstSentence(String s) {
        if (s == null || s.isBlank()) return "";
        int dot = s.indexOf('.');
        return dot > 0 && dot < 300 ? s.substring(0, dot + 1) : s.substring(0, Math.min(200, s.length()));
    }

    private static String firstParagraph(String s) {
        if (s == null || s.isBlank()) return "";
        int nl = s.indexOf('\n');
        return nl > 0 ? s.substring(0, nl).strip() : s;
    }
}
