package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.playbook.PlaybookOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;
import ai.genesisbrands.service.BlobStorageService;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Deterministic, non-LLM PDF renderer for the Brand Book Assembly deliverable.
 * Combines the exact facts from the source specialist outputs (hex codes, font names,
 * logo mark) with BrandBookAgent's client-facing prose into a single PDF via
 * openhtmltopdf (HTML+CSS -> PDF). No network calls: the DALLE logo image is read
 * directly from Blob Storage using the same blob-path pattern as LogoEvaluator.
 *
 * openhtmltopdf supports CSS 2.1 plus a partial CSS3 subset — no flexbox/grid in its
 * box-layout engine — so the template below is deliberately block/table based. This
 * is unrelated to playground.html's on-screen CSS, which can keep using flex/grid.
 *
 * Typography is deliberately not pixel-perfect: fetching arbitrary Google Fonts by
 * name at render time would be unreliable and out of scope. Inter (SIL OFL) is
 * bundled as the only rendering typeface; the brand's recommended headline/body fonts
 * are rendered as labeled captions instead of reproduced exactly.
 */
@Service
public class BrandBookPdfRenderer {

    private static final String ASSET_URL_PREFIX = "/api/assets/";

    private final BlobStorageService blobStorageService;

    public BrandBookPdfRenderer(BlobStorageService blobStorageService) {
        this.blobStorageService = blobStorageService;
    }

    public byte[] render(BrandBookInput input, BrandBookOutput output) {
        String html = buildHtml(input, output);

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());
            builder.useFont(() -> getClass().getResourceAsStream("/fonts/Inter-Regular.ttf"),
                "Inter", 400, FontStyle.NORMAL, true);
            builder.useFont(() -> getClass().getResourceAsStream("/fonts/Inter-Bold.ttf"),
                "Inter", 700, FontStyle.NORMAL, true);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render brand book PDF: " + e.getMessage(), e);
        }
    }

    private String buildHtml(BrandBookInput input, BrandBookOutput output) {
        DirectionBrief brief = input.brief();
        DirectionBrief.BrandContext brand = brief.brand();
        PlaybookOutput playbook = input.playbook();
        CopyOutput copy = input.copy();
        VisualIdentityOutput visual = input.visualIdentity();
        LogoOutput logo = input.logo();

        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"utf-8\"/><style>").append(css()).append("</style></head><body>");

        // Cover page
        sb.append("<div class=\"cover\">");
        sb.append("<div class=\"cover-label\">Brand Book</div>");
        sb.append("<h1 class=\"cover-name\">").append(escapeHtml(brand.name())).append("</h1>");
        sb.append("<p class=\"cover-tagline\">").append(escapeHtml(copy.tagline())).append("</p>");
        sb.append("<p class=\"cover-direction\">").append(escapeHtml(brief.direction().name())).append(" DIRECTION</p>");
        sb.append("</div>");

        // Strategic summary
        sb.append(section("The Strategy", block(playbook.strategicSummary()) + block(playbook.brandFoundations())));

        // Welcome / how to use
        sb.append(section("Welcome", block(output.welcomeNote())));
        sb.append(section("How To Use This Guide", block(output.howToUseThisGuide())));

        // Verbal identity
        StringBuilder verbal = new StringBuilder();
        verbal.append("<p class=\"tagline-display\">\"").append(escapeHtml(copy.tagline())).append("\"</p>");
        verbal.append(block(copy.missionStatement()));
        verbal.append(block(playbook.verbalIdentity()));
        if (copy.toneGuide() != null && copy.toneGuide().principles() != null && !copy.toneGuide().principles().isEmpty()) {
            verbal.append("<ul class=\"principles\">");
            for (String p : copy.toneGuide().principles()) {
                verbal.append("<li>").append(escapeHtml(p)).append("</li>");
            }
            verbal.append("</ul>");
        }
        sb.append(section("Verbal Identity", verbal.toString()));

        // Color palette
        StringBuilder colors = new StringBuilder();
        colors.append("<table class=\"palette\"><tbody>");
        for (VisualIdentityOutput.ColorSwatch swatch : visual.colorPalette()) {
            colors.append("<tr>");
            colors.append("<td class=\"swatch\" style=\"background-color:").append(escapeHtml(swatch.hex())).append(";\"></td>");
            colors.append("<td class=\"swatch-info\">");
            colors.append("<div class=\"swatch-name\">").append(escapeHtml(swatch.name()))
                .append(" — ").append(escapeHtml(swatch.hex())).append("</div>");
            colors.append("<div class=\"swatch-role\">").append(escapeHtml(swatch.role())).append("</div>");
            colors.append("</td>");
            colors.append("</tr>");
        }
        colors.append("</tbody></table>");
        colors.append(block(output.colorUsageGuidelines()));
        sb.append(section("Color Palette", colors.toString()));

        // Typography
        StringBuilder type = new StringBuilder();
        type.append("<p class=\"font-caption\">Headline: <strong>")
            .append(escapeHtml(visual.typography().headlineFont())).append("</strong></p>");
        type.append("<p class=\"font-caption\">Body: <strong>")
            .append(escapeHtml(visual.typography().bodyFont())).append("</strong></p>");
        type.append(block(output.typographyUsageGuidelines()));
        sb.append(section("Typography", type.toString()));

        // Logo
        StringBuilder logoSection = new StringBuilder();
        logoSection.append("<div class=\"logo-mark\">").append(logoMarkup(logo)).append("</div>");
        logoSection.append(block(playbook.logoRationale()));
        logoSection.append(block(output.logoUsageGuidelines()));
        if (output.logoDonts() != null && !output.logoDonts().isEmpty()) {
            logoSection.append("<ul class=\"donts\">");
            for (String d : output.logoDonts()) {
                logoSection.append("<li>").append(escapeHtml(d)).append("</li>");
            }
            logoSection.append("</ul>");
        }
        sb.append(section("Logo", logoSection.toString()));

        // Recommended applications
        if (playbook.recommendedApplications() != null && !playbook.recommendedApplications().isEmpty()) {
            StringBuilder apps = new StringBuilder("<ul class=\"principles\">");
            for (String a : playbook.recommendedApplications()) {
                apps.append("<li>").append(escapeHtml(a)).append("</li>");
            }
            apps.append("</ul>");
            sb.append(section("Recommended Applications", apps.toString()));
        }

        // Closing note
        sb.append(section("Closing Note", block(output.closingNote())));

        sb.append("</body></html>");
        return sb.toString();
    }

    private String logoMarkup(LogoOutput logo) {
        if (logo.method() == LogoOutput.Method.SVG_CONCEPT && logo.svgMarkup() != null) {
            return logo.svgMarkup();
        }
        if (logo.method() == LogoOutput.Method.DALLE && logo.imageUrl() != null) {
            byte[] bytes = fetchImageBytes(logo.imageUrl());
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "<img class=\"logo-image\" src=\"data:image/png;base64," + base64 + "\"/>";
        }
        return "";
    }

    private byte[] fetchImageBytes(String imageUrl) {
        String blobPath = imageUrl.startsWith(ASSET_URL_PREFIX)
            ? imageUrl.substring(ASSET_URL_PREFIX.length())
            : imageUrl;
        try {
            return blobStorageService.download(blobPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load logo image for brand book PDF: " + e.getMessage(), e);
        }
    }

    private String section(String heading, String bodyHtml) {
        return "<div class=\"section\"><h2>" + escapeHtml(heading) + "</h2>" + bodyHtml + "</div>";
    }

    private String block(String text) {
        if (text == null || text.isBlank()) return "";
        return "<p>" + escapeHtml(text) + "</p>";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String css() {
        return """
            @page { size: A4; margin: 2.5cm; }
            body { font-family: 'Inter', sans-serif; color: #1a1a1a; font-size: 11pt; line-height: 1.5; }
            h1, h2 { font-weight: 700; margin: 0 0 0.4em 0; }
            .cover { page-break-after: always; text-align: center; margin-top: 8cm; }
            .cover-label { text-transform: uppercase; letter-spacing: 0.2em; color: #888; font-size: 10pt; }
            .cover-name { font-size: 32pt; margin-top: 0.5em; }
            .cover-tagline { font-size: 14pt; font-style: italic; margin-top: 0.3em; }
            .cover-direction { text-transform: uppercase; letter-spacing: 0.15em; color: #888; font-size: 9pt; margin-top: 1.5em; }
            .section { page-break-after: always; }
            .section h2 { font-size: 18pt; border-bottom: 1px solid #ddd; padding-bottom: 0.3em; margin-bottom: 0.8em; }
            .tagline-display { font-size: 16pt; font-style: italic; margin-bottom: 0.6em; }
            .principles, .donts { padding-left: 1.2em; }
            .principles li, .donts li { margin-bottom: 0.3em; }
            table.palette { width: 100%; border-collapse: collapse; margin-bottom: 1em; }
            table.palette td { padding: 0.4em 0.6em; vertical-align: middle; border-bottom: 1px solid #eee; }
            td.swatch { width: 15%; height: 1.4cm; border: 1px solid #ddd; }
            .swatch-name { font-weight: 700; }
            .swatch-role { color: #666; font-size: 9pt; }
            .font-caption { font-size: 13pt; }
            .logo-mark { text-align: center; margin-bottom: 1em; }
            .logo-image { max-width: 60%; max-height: 6cm; }
            .logo-mark svg { max-width: 60%; max-height: 6cm; }
            """;
    }
}
