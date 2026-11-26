package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.core.BrandNames;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.logo.LogoVariantService;
import ai.genesisbrands.agent.playbook.PlaybookOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;
import ai.genesisbrands.model.BrandBookTemplate;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.BrandBookTemplateService;
import ai.genesisbrands.service.ColorSpecificationService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Thread-safety: Playwright for Java is not thread-safe. Every object created from a
 * Playwright instance must be used on the thread that created it. Sharing one Browser
 * across request threads made concurrent renders (the dashboard loads several direction
 * previews at once) fail with "Object doesn't exist" / "Cannot find object to call close".
 * So every render runs on a small pool of render threads, each owning its own Playwright
 * and Chromium (genesis.brand-book.render-threads, default 2), and opens its own
 * BrowserContext and Page, closed in a finally block.
 */
@Service
public class BrandBookTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(BrandBookTemplateRenderer.class);
    private static final String ASSET_URL_PREFIX = "/api/assets/";

    private final BlobStorageService blobStorageService;
    private final LogoVariantService logoVariantService;
    private final BrandBookTemplateService brandBookTemplateService;

    private final int renderThreads;

    /** Resolves the agent's typeface names to Google Fonts families that load; replaceable in tests. */
    private GoogleFonts fonts = GoogleFonts.live();

    void useFonts(GoogleFonts fonts) {
        this.fonts = fonts;
    }

    /** One per render thread; only ever touched on that thread (except at shutdown). */
    private static final class RenderWorker {
        Playwright playwright;
        Browser browser;
    }

    private final ThreadLocal<RenderWorker> threadWorker = new ThreadLocal<>();
    private final List<RenderWorker> workers = new CopyOnWriteArrayList<>();
    private ExecutorService renderPool;

    public BrandBookTemplateRenderer(BlobStorageService blobStorageService,
                                     LogoVariantService logoVariantService,
                                     BrandBookTemplateService brandBookTemplateService,
                                     @Value("${genesis.brand-book.render-threads:2}") int renderThreads) {
        this.blobStorageService = blobStorageService;
        this.logoVariantService = logoVariantService;
        this.brandBookTemplateService = brandBookTemplateService;
        this.renderThreads = Math.max(1, renderThreads);
    }

    @PostConstruct
    void init() {
        AtomicInteger n = new AtomicInteger();
        renderPool = Executors.newFixedThreadPool(renderThreads, r -> {
            Thread t = new Thread(r, "brand-book-render-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        log.info("BrandBookTemplateRenderer: {} render thread(s), Chromium launched on first use", renderThreads);
    }

    @PreDestroy
    void destroy() {
        renderPool.shutdownNow();
        for (RenderWorker w : workers) {
            if (w.browser != null) try { w.browser.close(); } catch (Exception ignored) {}
            if (w.playwright != null) try { w.playwright.close(); } catch (Exception ignored) {}
        }
    }

    /** Runs work on a render thread with that thread's own Browser, and waits for the result. */
    private <T> T onRenderThread(Function<Browser, T> work) {
        Future<T> future = renderPool.submit(() -> work.apply(browser()));
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException("Brand book render failed", e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for brand book render", e);
        }
    }

    /**
     * The calling render thread's Browser, launched on first use. The Chromium process can
     * crash or have its pipe closed independently of the JVM (OOM, signal, host hiccup);
     * Playwright surfaces that as "connection closed" on the next call, so a disconnected
     * browser is relaunched, with a fresh Playwright driver (its pipe may be the part that
     * died), instead of failing every later render.
     */
    private Browser browser() {
        RenderWorker w = threadWorker.get();
        if (w == null) {
            w = new RenderWorker();
            threadWorker.set(w);
            workers.add(w);
        }
        if (w.browser == null || !w.browser.isConnected()) {
            if (w.browser != null) {
                log.warn("BrandBookTemplateRenderer: Playwright browser not connected — relaunching");
                try { w.browser.close(); } catch (Exception ignored) {}
                try { w.playwright.close(); } catch (Exception ignored) {}
                w.playwright = null;
            }
            if (w.playwright == null) w.playwright = Playwright.create();
            w.browser = w.playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            log.info("BrandBookTemplateRenderer: Chromium started on {}", Thread.currentThread().getName());
        }
        return w.browser;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] render(BrandBookInput input, BrandBookOutput output) {
        BrandBookTemplate template = brandBookTemplateService.select(
            input.brief().brand(), input.brief().direction());
        Set<String> includedSections = brandBookTemplateService.resolveIncludedSections(template);
        String templateHtml = brandBookTemplateService.loadHtml(template);
        return renderHtml(buildHtml(input, output, templateHtml, includedSections), false);
    }

    /**
     * Render with an explicit template rather than auto-selection.
     * Used by the admin template preview endpoint.
     */
    public byte[] renderWithTemplate(BrandBookInput input, BrandBookOutput output, BrandBookTemplate template) {
        Set<String> includedSections = brandBookTemplateService.resolveIncludedSections(template);
        String templateHtml = brandBookTemplateService.loadHtml(template);
        return renderHtml(buildHtml(input, output, templateHtml, includedSections), false);
    }

    /**
     * Print-ready variant: 3 mm bleed on all sides (216×303 mm page) with
     * corner crop marks rendered as an SVG overlay. Content is identical to
     * the screen PDF; only the @page size and the crop-mark layer differ.
     */
    public byte[] renderPrintReady(BrandBookInput input, BrandBookOutput output) {
        BrandBookTemplate template = brandBookTemplateService.select(
            input.brief().brand(), input.brief().direction());
        Set<String> includedSections = brandBookTemplateService.resolveIncludedSections(template);
        String templateHtml = brandBookTemplateService.loadHtml(template);
        String html = buildHtml(input, output, templateHtml, includedSections);
        html = injectPrintBleedCss(html);
        return renderHtml(html, true);
    }

    /**
     * Watermarked JPEG preview of the cover page — the free, pre-payment preview shown
     * on the results page before a direction is chosen. Same HTML build as render(),
     * but skips PDF generation entirely and screenshots just the first .page.
     */
    public byte[] renderPreviewImage(BrandBookInput input, BrandBookOutput output) {
        BrandBookTemplate template = brandBookTemplateService.select(
            input.brief().brand(), input.brief().direction());
        Set<String> includedSections = brandBookTemplateService.resolveIncludedSections(template);
        String templateHtml = brandBookTemplateService.loadHtml(template);
        String html = injectPreviewWatermarkCss(buildHtml(input, output, templateHtml, includedSections));
        return renderPreviewScreenshot(html);
    }

    // ── Section filtering ─────────────────────────────────────────────────────

    /**
     * Remove optional sections not present in includedSectionIds from the HTML.
     * Sections are delimited by <!-- BEGIN-SECTION:id --> and <!-- END-SECTION:id -->.
     * If includedSectionIds is empty we skip filtering (backward compat with templates
     * that have no section markers).
     */
    private static String filterSections(String html, Set<String> includedSectionIds) {
        if (includedSectionIds.isEmpty()) return html;
        // Match BEGIN-SECTION:id ... END-SECTION:id blocks (including the markers)
        Pattern p = Pattern.compile(
            "<!-- BEGIN-SECTION:([^-]+) -->\\n?(.+?)<!-- END-SECTION:\\1 -->\\n?",
            Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String sectionId = m.group(1);
            if (includedSectionIds.contains(sectionId)) {
                // Keep content between markers (strip the markers themselves)
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(2)));
            } else {
                m.appendReplacement(sb, "");
                log.debug("filterSections: stripped section '{}'", sectionId);
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    byte[] renderHtml(String html, boolean printReady) {
        return onRenderThread(browser -> renderHtml(browser, html, printReady));
    }

    private byte[] renderHtml(Browser browser, String html, boolean printReady) {
        // Write HTML to a temp file and navigate via file:// rather than using setContent().
        // setContent() passes the full HTML string over the Playwright IPC pipe; when the
        // template contains many base64-encoded photos the string can exceed tens of MB and
        // Chromium crashes with "Page crashed". file:// navigation reads from disk directly
        // and bypasses the IPC buffer limit entirely.
        Path tmpFile = null;
        BrowserContext ctx = browser.newContext();
        try {
            tmpFile = Files.createTempFile("brand-book-", ".html");
            Files.writeString(tmpFile, html, StandardCharsets.UTF_8);
            Page tab = ctx.newPage();
            tab.navigate("file://" + tmpFile.toAbsolutePath(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            awaitFonts(tab);
            Page.PdfOptions opts = new Page.PdfOptions().setPrintBackground(true);
            if (printReady) {
                opts.setWidth("216mm").setHeight("303mm");
            } else {
                opts.setPreferCSSPageSize(true);
            }
            return tab.pdf(opts);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write temp HTML file for PDF render", e);
        } finally {
            ctx.close();
            if (tmpFile != null) {
                try { Files.delete(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Screenshots only the first .page element as a JPEG, downscaled via a reduced
     * deviceScaleFactor rather than CSS: .page has a fixed physical size (A4 in mm) set
     * by the template regardless of viewport, so only the device scale factor actually
     * shrinks the captured pixels. 0.756 maps an A4 page (~794×1123 CSS px) to ~600×849.
     */
    byte[] renderPreviewScreenshot(String html) {
        return onRenderThread(browser -> renderPreviewScreenshot(browser, html));
    }

    private byte[] renderPreviewScreenshot(Browser browser, String html) {
        Path tmpFile = null;
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(900, 1300)
            .setDeviceScaleFactor(0.756));
        try {
            tmpFile = Files.createTempFile("brand-book-preview-", ".html");
            Files.writeString(tmpFile, html, StandardCharsets.UTF_8);
            Page tab = ctx.newPage();
            tab.navigate("file://" + tmpFile.toAbsolutePath(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            awaitFonts(tab);
            return tab.locator(".page").first().screenshot(new Locator.ScreenshotOptions()
                .setType(ScreenshotType.JPEG)
                .setQuality(70));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write temp HTML file for preview render", e);
        } finally {
            ctx.close();
            if (tmpFile != null) {
                try { Files.delete(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Network idle is not font readiness: Chromium can still be decoding a webfont when it
     * prints, and that page then comes out in the fallback face.
     */
    private static void awaitFonts(Page tab) {
        try {
            tab.evaluate("document.fonts.ready.then(() => true)");
        } catch (RuntimeException e) {
            log.debug("document.fonts.ready failed: {}", e.getMessage());
        }
    }

    private static String injectPreviewWatermarkCss(String html) {
        // Tiled, rotated "PREVIEW" stamp reusing .page::after — free on the non-print-ready
        // path since injectPrintBleedCss only occupies ::after when printReady is true.
        String watermarkSvg = "<svg xmlns='http://www.w3.org/2000/svg' width='240' height='160'>"
            + "<text x='-20' y='95' font-family='sans-serif' font-size='26' font-weight='700' "
            + "fill='rgba(0,0,0,0.14)' transform='rotate(-30 120 80)'>PREVIEW</text></svg>";
        String watermarkB64 = Base64.getEncoder()
            .encodeToString(watermarkSvg.getBytes(StandardCharsets.UTF_8));

        String watermarkCss = "\n/* Preview watermark: tiled rotated PREVIEW stamp */\n"
            + ".page::after {\n"
            + "  content: '';\n"
            + "  position: absolute;\n"
            + "  top: 0; left: 0; width: 100%; height: 100%;\n"
            + "  background: url(\"data:image/svg+xml;base64," + watermarkB64 + "\") repeat;\n"
            + "  pointer-events: none;\n"
            + "  z-index: 99999;\n"
            + "}\n";

        int idx = html.lastIndexOf("</style>");
        if (idx < 0) return html;
        return html.substring(0, idx) + watermarkCss + html.substring(idx);
    }

    /**
     * A raster logo fits its slot by default, but at zero specificity, so each template's own
     * size cap for that slot ({@code .e-card-logo img { max-height: 12mm }}) still wins. Inline
     * limits beat those caps, and a logo then grew past its slot.
     */
    private static volatile String componentsCss;

    /** Defaults for every block the renderer generates (don'ts, grids, mock-ups, tone), shared by all templates. */
    static String componentsCss() {
        String css = componentsCss;
        if (css == null) {
            try (InputStream in = BrandBookTemplateRenderer.class.getResourceAsStream("/templates/brand-book/_components.css")) {
                if (in == null) throw new IllegalStateException("_components.css missing from the classpath");
                css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // Inside <style>, the text "</style" ends the element wherever it appears, comments included.
                if (css.toLowerCase(Locale.ROOT).contains("</style")) {
                    throw new IllegalStateException("_components.css must not contain the text </style");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            componentsCss = css;
        }
        return css;
    }

    static String injectRasterLogoCss(String html) {
        String css = "\n:where(img." + RASTER_LOGO_CLASS + ") { max-width: 100%; max-height: 100%; object-fit: contain; }\n"
            + componentsCss() + "\n";
        int idx = html.indexOf("</style>");
        if (idx < 0) return html;
        return html.substring(0, idx) + css + html.substring(idx);
    }

    private static String injectPrintBleedCss(String html) {
        // 3 mm bleed: page grows to 216×303 mm; crop marks are drawn inside the bleed
        // zone as thin black L-lines at each corner of the 210×297 mm trim box.
        String cropMarksSvg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 216 303'>"
            + "<line x1='0' y1='3' x2='2' y2='3' stroke='#000' stroke-width='0.3'/>"
            + "<line x1='3' y1='0' x2='3' y2='2' stroke='#000' stroke-width='0.3'/>"
            + "<line x1='214' y1='3' x2='216' y2='3' stroke='#000' stroke-width='0.3'/>"
            + "<line x1='213' y1='0' x2='213' y2='2' stroke='#000' stroke-width='0.3'/>"
            + "<line x1='0' y1='300' x2='2' y2='300' stroke='#000' stroke-width='0.3'/>"
            + "<line x1='3' y1='301' x2='3' y2='303' stroke='#000' stroke-width='0.3'/>"
            + "<line x1='214' y1='300' x2='216' y2='300' stroke='#000' stroke-width='0.3'/>"
            + "<line x1='213' y1='301' x2='213' y2='303' stroke='#000' stroke-width='0.3'/>"
            + "</svg>";
        String cropMarksB64 = Base64.getEncoder()
            .encodeToString(cropMarksSvg.getBytes(StandardCharsets.UTF_8));

        String printCss = "\n/* Print-ready: 3 mm bleed + crop marks */\n"
            + "@page { size: 216mm 303mm; margin: 0; }\n"
            + ".page { width: 216mm !important; height: 303mm !important; }\n"
            + ".page::after {\n"
            + "  content: '';\n"
            + "  position: absolute;\n"
            + "  top: 0; left: 0; width: 100%; height: 100%;\n"
            + "  background: url(\"data:image/svg+xml;base64," + cropMarksB64 + "\") no-repeat 0 0;\n"
            + "  background-size: 100% 100%;\n"
            + "  pointer-events: none;\n"
            + "  z-index: 99999;\n"
            + "}\n";

        // Inject before the last </style> in the <head> so it overrides the base @page rule
        int idx = html.lastIndexOf("</style>");
        if (idx < 0) return html;
        return html.substring(0, idx) + printCss + html.substring(idx);
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    String buildHtml(BrandBookInput input, BrandBookOutput output,
                             String templateHtml, Set<String> includedSections) {
        DirectionBrief brief = input.brief();
        DirectionBrief.BrandContext brand = brief.brand();
        CopyOutput copy = input.copy();
        VisualIdentityOutput visual = input.visualIdentity();
        LogoOutput logo = input.logo();
        List<VisualIdentityOutput.ColorSwatch> palette = visual.colorPalette();
        // "One Way Coffee LTD" is the company; the book is about the brand people see.
        String brandName = BrandNames.tradingName(brand.name());

        // Colors
        String primaryHex   = findSwatchHex(palette, "primary",   0);
        String secondaryHex = findSwatchHex(palette, "secondary", 1);
        String accentHex    = findSwatchHex(palette, "accent",    2);
        String neutralHex   = findSwatchHex(palette, "neutral",   3);
        String primaryDark  = darken(primaryHex, 0.35);
        String primaryLight = lighten(primaryHex, 0.75);
        String primaryInk   = legibleOnWhite(primaryHex, palette, MIN_TEXT_CONTRAST, Fallback.DEEPEST);
        String primaryPanel = legibleOnWhite(primaryHex, palette, MIN_PANEL_CONTRAST, Fallback.MOST_COLOUR);

        // Typography: only families and weights Google Fonts really serves, so the page loads them.
        GoogleFonts.Face heading = fonts.resolve(visual.typography().headlineFont(), GoogleFonts.FALLBACK_HEADING);
        GoogleFonts.Face body    = fonts.resolve(visual.typography().bodyFont(), GoogleFonts.FALLBACK_BODY);

        // Logo markup and variants
        String logoMarkup   = buildLogoMarkup(logo, brandName);
        boolean hasSymbol   = logo.symbolImageUrl() != null;
        String logoSymbol   = hasSymbol ? rasterMarkup(logo.symbolImageUrl(), logoMarkup) : logoMarkup;
        String logoWhite    = buildLogoVariant(logo, logo.imageUrl(), "#ffffff", logoMarkup, brandName);
        String logoMono     = buildLogoVariant(logo, logo.imageUrl(), primaryInk, logoMarkup, brandName);
        String logoOff      = buildLogoVariant(logo, logo.imageUrl(), BookBlocks.offBrand(accentHex), logoMarkup, brandName);
        String symbolWhite  = hasSymbol
            ? buildLogoVariant(logo, logo.symbolImageUrl(), "#ffffff", logoSymbol, brandName) : logoWhite;

        // Photos, each fetched once (empty string = CSS background-color fallback)
        Map<String, String> photos = new HashMap<>();
        for (String slot : PHOTO_SLOTS) {
            photos.put(slot, fetchPhotoDataUri(brief.engagementId(), brief.direction().name(), slot));
        }

        String year = String.valueOf(Year.now().getValue());
        BookBlocks.Ctx ctx = new BookBlocks.Ctx(brandName, copy.tagline(), copy.elevatorPitch(),
            copy.missionStatement(), copy.values(), logoMarkup, logoWhite, logoOff, logoSymbol, symbolWhite,
            hasSymbol, photos, primaryHex, primaryPanel, primaryInk, secondaryHex, accentHex, neutralHex,
            palette, heading.family(), year);

        VisualIdentityOutput.GraphicDevicesSpec devices = visual.graphicDevices();
        String iconUsageNote = devices != null && devices.iconUsageNote() != null ? devices.iconUsageNote() : "";

        Map<String, String> subs = new LinkedHashMap<>();
        subs.put("{{FONT_LINK_HREF}}",    GoogleFonts.stylesheetHref(heading, body));
        subs.put("{{HEADLINE_FONT_URL}}", GoogleFonts.familyParam(heading.family()));
        subs.put("{{BODY_FONT_URL}}",     GoogleFonts.familyParam(body.family()));
        subs.put("{{HEADLINE_FONT}}",     esc(heading.family()));
        subs.put("{{BODY_FONT}}",         esc(body.family()));
        putSpecimen(subs, "HW", heading);
        putSpecimen(subs, "BW", body);
        subs.put("{{PRIMARY_HEX}}",       primaryHex);
        subs.put("{{PRIMARY_DARK_HEX}}",  primaryDark);
        subs.put("{{PRIMARY_LIGHT_HEX}}", primaryLight);
        subs.put("{{PRIMARY_INK_HEX}}",   primaryInk);
        subs.put("{{PRIMARY_PANEL_HEX}}", primaryPanel);
        subs.put("{{SECONDARY_HEX}}",     secondaryHex);
        subs.put("{{ACCENT_HEX}}",        accentHex);
        subs.put("{{NEUTRAL_HEX}}",       neutralHex);
        subs.put("{{BRAND_NAME_UPPER}}",  esc(brandName.toUpperCase(Locale.ROOT)));
        subs.put("{{BRAND_NAME}}",        esc(brandName));
        subs.put("{{TAGLINE}}",           esc(copy.tagline()));
        subs.put("{{DIRECTION}}",         brief.direction().name());
        subs.put("{{YEAR}}",              year);
        subs.put("{{ELEVATOR_PITCH}}",    esc(TextFit.fit(copy.elevatorPitch(), 420)));
        subs.put("{{WELCOME_LETTER}}",    esc(TextFit.fit(firstParagraph(output.welcomeLetterOpening() != null
                                              ? output.welcomeLetterOpening() : output.welcomeNote()), 520)));
        subs.put("{{HOW_TO_USE}}",        esc(TextFit.fit(output.howToUseThisGuide(), 260)));
        subs.put("{{MISSION_STATEMENT}}", esc(TextFit.fit(copy.missionStatement(), 260)));
        subs.put("{{MISSION_STATEMENT_TRUNCATED}}", esc(TextFit.fit(copy.missionStatement(), 90)));
        subs.put("{{BRAND_STORY_SNIPPET}}", esc(TextFit.snippet(copy.brandStory(), 120, 320)));
        subs.put("{{VISION_PILLARS_HTML}}", buildVisionHtml(copy.vision()));
        subs.put("{{VALUES_HTML}}",       buildValuesHtml(copy.values()));
        subs.put("{{LOGO_WHITEOUT}}",     logoWhite);
        subs.put("{{LOGO_MONO}}",         logoMono);
        subs.put("{{LOGO_PRIMARY}}",      logoMarkup);
        subs.put("{{LOGO_SYMBOL}}",       logoSymbol);
        subs.put("{{LOGO_CLEARANCE_SVG}}", BookBlocks.clearSpace(ctx));
        subs.put("{{LOGO_USAGE}}",        esc(TextFit.fit(output.logoUsageGuidelines(), 420)));
        subs.put("{{LOGO_DONTS_HTML}}",   BookBlocks.donts(output.logoDonts(), ctx));
        subs.put("{{SWATCH_SECTION_HTML}}", buildSwatchHtml(palette));
        subs.put("{{GRADIENT_SECTION_HTML}}", buildGradientHtml(visual.gradients()));
        subs.put("{{STROKE_VARIANTS_HTML}}", BookBlocks.strokes(devices != null ? devices.strokeVariants() : List.of(), ctx));
        subs.put("{{BOX_VARIANTS_HTML}}",  BookBlocks.boxes(devices != null ? devices.boxVariants() : List.of(), ctx));
        subs.put("{{ICON_USAGE_NOTE}}",   esc(TextFit.fit(iconUsageNote, 260)));
        subs.put("{{IMAGERY_GUIDANCE}}",  esc(TextFit.fit(output.imageryGuidance(), 420)));
        subs.put("{{TYPEFACE_RATIONALE}}", esc(TextFit.fit(output.typefaceRationale(), 420)));
        subs.put("{{TYPOGRAPHY_USAGE}}",  esc(TextFit.fit(output.typographyUsageGuidelines(), 420)));
        subs.put("{{COLOR_USAGE}}",       esc(TextFit.fit(output.colorUsageGuidelines(), 360)));
        subs.put("{{VOCABULARY_HTML}}",   buildVocabHtml(copy.vocabulary()));
        subs.put("{{TONE_GUIDE_HTML}}",   BookBlocks.tone(copy.toneGuide()));
        subs.put("{{CLOSING_NOTE}}",      esc(TextFit.fit(output.closingNote(), 420)));
        subs.put("{{GRID_PORTRAIT_HTML}}",  BookBlocks.grid(false, ctx));
        subs.put("{{GRID_LANDSCAPE_HTML}}", BookBlocks.grid(true, ctx));
        subs.put("{{APP_1_HTML}}",        BookBlocks.social(ctx));
        subs.put("{{APP_2_HTML}}",        BookBlocks.website(ctx));
        subs.put("{{APP_3_HTML}}",        BookBlocks.signage(ctx));
        subs.put("{{STATIONERY_HTML}}",   BookBlocks.stationery(ctx));
        subs.put("{{PHOTO_COVER_DATA}}",  ctx.photo("cover"));
        subs.put("{{PHOTO_MISSION_DATA}}", ctx.photo("mission"));
        for (int i = 1; i <= 5; i++) subs.put("{{PHOTO_IMAGERY_" + i + "_DATA}}", ctx.photo("imagery-" + i));
        for (int i = 1; i <= 3; i++) subs.put("{{PHOTO_EXAMPLES_" + i + "_DATA}}", ctx.photo("examples-" + i));
        subs.put("{{PHOTO_APP_DATA}}",    ctx.photo("stationery"));

        // Section filter first, so excluded sections are never numbered or listed in the contents.
        String html = paginate(filterSections(templateHtml, includedSections));
        subs.put("{{CONTENTS_HTML}}", buildContentsHtml(html));
        return injectRasterLogoCss(substitute(html, subs));
    }

    static final List<String> PHOTO_SLOTS = List.of("cover", "mission", "imagery-1", "imagery-2", "imagery-3",
        "imagery-4", "imagery-5", "examples-1", "examples-2", "examples-3", "stationery");

    /** {@code {{HW1}}…{{HW3}}} = weight, {@code _LABEL} = "Light 300", {@code _DISPLAY} hides unused rows. */
    private static void putSpecimen(Map<String, String> subs, String prefix, GoogleFonts.Face face) {
        List<Integer> weights = face.specimenWeights();
        for (int i = 1; i <= 3; i++) {
            boolean shown = i <= weights.size();
            int w = shown ? weights.get(i - 1) : 400;
            subs.put("{{" + prefix + i + "}}", String.valueOf(w));
            subs.put("{{" + prefix + i + "_LABEL}}", GoogleFonts.weightLabel(w) + " " + w);
            subs.put("{{" + prefix + i + "_DISPLAY}}", shown ? "" : "display:none");
        }
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private static final Pattern SECTION_OPEN = Pattern.compile("<section\\b[^>]*class=\"page");
    private static final Pattern PAGE_NUMBER = Pattern.compile(
        "(class=\"[^\"]*(?:ghost|footer-page|sidebar-num)[^\"]*\">)\\s*\\d{1,2}\\s*(<)");
    private static final Pattern DATA_TOC = Pattern.compile("^<section\\b[^>]*\\bdata-toc=\"([^\"]+)\"");

    /** The {@code <section class="page">} spans of html, in page order. */
    private static List<int[]> pageSpans(String html) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = SECTION_OPEN.matcher(html);
        int from = 0;
        while (m.find(from)) {
            int end = html.indexOf("</section>", m.start());
            if (end < 0) break;
            end += "</section>".length();
            spans.add(new int[]{m.start(), end});
            from = end;
        }
        return spans;
    }

    /**
     * Writes each page's real number (cover = 1) into its page-number slots. The templates carry
     * fixed numbers, which went wrong as soon as a section was filtered out or a page added, and
     * four application pages all said 22.
     */
    static String paginate(String html) {
        List<int[]> spans = pageSpans(html);
        StringBuilder sb = new StringBuilder(html.length());
        int last = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] s = spans.get(i);
            sb.append(html, last, s[0]);
            String page = html.substring(s[0], s[1]);
            String num = String.format("%02d", i + 1);
            sb.append(PAGE_NUMBER.matcher(page).replaceAll("$1" + num + "$2"));
            last = s[1];
        }
        return sb.append(html, last, html.length()).toString();
    }

    /** The contents list from the pages that start a chapter ({@code data-toc}), with their real page numbers. */
    static String buildContentsHtml(String html) {
        StringBuilder sb = new StringBuilder();
        List<int[]> spans = pageSpans(html);
        for (int i = 0; i < spans.size(); i++) {
            Matcher m = DATA_TOC.matcher(html.substring(spans.get(i)[0], Math.min(spans.get(i)[1], spans.get(i)[0] + 300)));
            if (!m.find()) continue;
            sb.append("<div class=\"contents-item\">")
              .append("<div class=\"contents-num\">").append(String.format("%02d", i + 1)).append("</div>")
              .append("<div class=\"contents-name\">").append(m.group(1)).append("</div>")
              .append("</div>");
        }
        return sb.toString();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[A-Z0-9_]+\\}\\}");

    /**
     * Fills every {@code {{TOKEN}}} in one pass. The photos are inlined as base64 data URIs, so
     * the page runs to megabytes, and replacing token by token copied all of it once per token
     * (some forty times a render): a few previews at once ran the heap out. Tokens with no entry
     * are left as written, and a value is never scanned for tokens itself.
     */
    static String substitute(String html, Map<String, String> subs) {
        java.util.regex.Matcher m = PLACEHOLDER.matcher(html);
        long extra = 0;
        for (String v : subs.values()) if (v != null) extra += v.length();
        StringBuilder sb = new StringBuilder((int) Math.min(Integer.MAX_VALUE - 8L, html.length() + extra));
        int last = 0;
        while (m.find()) {
            String token = m.group();
            if (!subs.containsKey(token)) continue;
            String value = subs.get(token);
            sb.append(html, last, m.start()).append(value != null ? value : "");
            last = m.end();
        }
        return sb.append(html, last, html.length()).toString();
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
            String pantone = pantoneFor(s);
            if (pantone != null) {
                // The nearest coated solid, recomputed here: the agent's own pick put greens on Black C.
                sb.append("<span class=\"swatch-spec-pill\"><strong>PANTONE</strong> &#8776; ")
                  .append(esc(pantone.replaceFirst("(?i)^pantone\\s+", ""))).append("</span>");
            }
            sb.append("</div>");
            sb.append("<div class=\"swatch-role\">").append(esc(TextFit.fit(s.role(), 140))).append("</div>");
            sb.append("</div></div>");
        }
        return sb.toString();
    }

    private static String pantoneFor(VisualIdentityOutput.ColorSwatch s) {
        if (s.hex() != null && s.hex().matches("#?[0-9A-Fa-f]{6}")) {
            return ColorSpecificationService.nearestPantoneStatic(s.hex());
        }
        return s.pantone();
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

    // ── Asset helpers ─────────────────────────────────────────────────────────

    private String buildLogoMarkup(LogoOutput logo, String brandName) {
        if (logo.method() == LogoOutput.Method.SVG_CONCEPT && logo.svgMarkup() != null) {
            String svg = logo.svgMarkup().strip();
            if (svg.startsWith("<?xml")) {
                int end = svg.indexOf("?>") + 2;
                svg = svg.substring(end).strip();
            }
            return svg;
        }
        if (logo.isImage() && logo.imageUrl() != null) {
            return rasterMarkup(logo.imageUrl(), buildTextLogoSvg(brandName));
        }
        return buildTextLogoSvg(brandName);
    }

    /** An inline PNG from blob storage, or {@code fallback} when it cannot be loaded. */
    static final String RASTER_LOGO_CLASS = "bb-raster-logo";

    private String rasterMarkup(String imageUrl, String fallback) {
        return rasterMarkup(imageUrl, fallback, "");
    }

    private String rasterMarkup(String imageUrl, String fallback, String extraStyle) {
        byte[] bytes = rasterBytes(imageUrl);
        return bytes != null ? imgTag(bytes, extraStyle) : fallback;
    }

    /** The trimmed, knocked-out PNG behind a logo URL, or null when it cannot be loaded. */
    private byte[] rasterBytes(String imageUrl) {
        String blobPath = imageUrl.startsWith(ASSET_URL_PREFIX)
            ? imageUrl.substring(ASSET_URL_PREFIX.length())
            : imageUrl;
        try {
            return LogoKnockout.apply(LogoTrim.trim(blobStorageService.download(blobPath)));
        } catch (Exception e) {
            log.warn("Cannot load logo image {} for brand book (using fallback): {}", blobPath, e.getMessage());
            return null;
        }
    }

    private static String imgTag(byte[] png, String extraStyle) {
        // Size limits come from injectRasterLogoCss, never inline, so template caps apply.
        return "<img class=\"" + RASTER_LOGO_CLASS + "\" src=\"data:image/png;base64,"
            + Base64.getEncoder().encodeToString(png) + "\""
            + (extraStyle.isEmpty() ? "" : " style=\"" + extraStyle + "\"") + ">";
    }

    /**
     * The logo (or its symbol, {@code imageUrl}) in one flat colour. SVG concepts are recoloured
     * as markup. A raster logo with transparency is recoloured pixel by pixel, keeping its alpha,
     * so the white, one-colour and off-brand versions are the real mark. A raster without
     * transparency has a background a recolour would flood, so white falls back to a CSS filter
     * and every other colour to the primary logo.
     */
    private String buildLogoVariant(LogoOutput logo, String imageUrl, String targetColor,
                                    String primaryMarkup, String brandName) {
        if (logo.method() == LogoOutput.Method.SVG_CONCEPT && logo.svgMarkup() != null && logoVariantService != null) {
            String svg = logo.svgMarkup().strip();
            if (svg.startsWith("<?xml")) svg = svg.substring(svg.indexOf("?>") + 2).strip();
            return logoVariantService.recolor(svg, targetColor);
        }
        if (logo.isImage() && imageUrl != null) {
            byte[] bytes = rasterBytes(imageUrl);
            if (bytes != null) {
                byte[] flat = recolour(bytes, targetColor);
                if (flat != null) return imgTag(flat, "");
                if ("#ffffff".equalsIgnoreCase(targetColor)) return imgTag(bytes, "filter:brightness(0) invert(1);");
            }
        }
        return primaryMarkup;
    }

    /** Every pixel painted {@code hex}, alpha kept; null when the image has no alpha channel. */
    static byte[] recolour(byte[] png, String hex) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null || !src.getColorModel().hasAlpha()) return null;
            int[] c = parseHex(hex);
            int rgb = (c[0] << 16) | (c[1] << 8) | c[2];
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    int a = src.getRGB(x, y) >>> 24;
                    out.setRGB(x, y, (a << 24) | rgb);
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static String buildTextLogoSvg(String brandName) {
        String safe = brandName
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace("\"", "&quot;");
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 480 120\" width=\"100%\" height=\"100%\">"
            + "<text x=\"50%\" y=\"62%\" text-anchor=\"middle\" dominant-baseline=\"middle\" "
            + "font-size=\"52\" font-weight=\"800\" fill=\"currentColor\" "
            + "font-family=\"Georgia, 'Times New Roman', serif\" letter-spacing=\"3\">"
            + safe + "</text></svg>";
    }

    /**
     * A slot's photo: the direction's own first ({@code assets/photos/<eng>/<DIRECTION>/<slot>.jpg}),
     * then the engagement-wide one older engagements have, which all three books shared.
     */
    private String fetchPhotoDataUri(String engagementId, String direction, String slot) {
        for (String blobPath : List.of("assets/photos/%s/%s/%s.jpg".formatted(engagementId, direction, slot),
                                       "assets/photos/%s/%s.jpg".formatted(engagementId, slot))) {
            try {
                byte[] bytes = blobStorageService.download(blobPath);
                if (bytes != null && bytes.length > 0) {
                    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
                }
            } catch (Exception e) {
                log.debug("Photo unavailable at {}: {}", blobPath, e.getMessage());
            }
        }
        return "";
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    /** Role words that fence a colour off; such a swatch must not become the book's working accent. */
    private static final Pattern RESTRICTED_ROLE = Pattern.compile("(?i)\\b(reserved|exclusive(ly)?|only (for|on|in))\\b");

    static String findSwatchHex(List<VisualIdentityOutput.ColorSwatch> palette, String roleKeyword, int fallbackIndex) {
        if (palette == null || palette.isEmpty()) return "#1a1a1a";
        String restrictedMatch = null;
        for (VisualIdentityOutput.ColorSwatch s : palette) {
            if (s.role() != null && s.role().toLowerCase().contains(roleKeyword)) {
                if (!RESTRICTED_ROLE.matcher(s.role()).find()) return s.hex();
                if (restrictedMatch == null) restrictedMatch = s.hex();
            }
        }
        if (restrictedMatch != null) return restrictedMatch;
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

    /** WCAG AA for body-sized type: what {@code --primary-ink} guarantees against the white page. */
    private static final double MIN_TEXT_CONTRAST = 4.5;

    /**
     * WCAG AA for large type and UI surfaces: what {@code --primary-panel} guarantees, so white
     * display type reads on a brand-coloured fill. Deliberately lower than
     * {@link #MIN_TEXT_CONTRAST} — holding panels to the body-text bar would replace perfectly
     * good mid-tone brand colours with near-black and flatten the book (Kindling Retrofit's
     * EVOLVED clay is 3.51:1: right for a cover, too weak for a caption).
     */
    private static final double MIN_PANEL_CONTRAST = 3.0;

    /**
     * A brand colour that is guaranteed to separate from white by {@code minRatio}, for type on
     * the white page ({@code --primary-ink}) and for fills that carry white type
     * ({@code --primary-panel}).
     *
     * <p>A palette's primary is chosen to look right as a fill, and the Visual Identity agent may
     * legitimately give that role to a pale paper colour — Kindling Retrofit's primary was
     * {@code #F3ECE1}, 1.17:1 against white. Every template paints its labels, headings, section
     * numbers and the statement pull-quote in the primary, and fills its sidebar, cover and
     * closing page with it, so that book's type was all but invisible both ways round
     * (found 2026-11-23).
     *
     * <p>Resolution order: the primary itself when it already clears the ratio, so a well-chosen
     * palette renders exactly as before; else the palette's own darkest passing swatch, because a
     * palette that calls one colour "Paper" almost always carries a deep companion the designer
     * named for ink; else the primary stepped toward black, which is legible but loses the hue a
     * near-neutral never had.
     */
    /**
     * Which passing swatch to borrow when the primary itself is unusable. Both are legible; they
     * differ in what they sacrifice.
     */
    enum Fallback {
        /**
         * The deepest swatch that passes — what type wants. A palette that calls one colour
         * "Paper" almost always carries a deep companion the designer named for ink, and a
         * heading set in it reads as deliberate rather than as an accent that strayed into
         * body copy.
         */
        DEEPEST,
        /**
         * The passing swatch closest to the threshold — what a fill wants. A book whose cover,
         * sidebar and chrome bars all fell back to the palette's near-black would read as a
         * different brand, so a panel keeps as much of the palette's own colour as the ratio
         * allows: Kindling Retrofit's rust rather than its bark.
         */
        MOST_COLOUR
    }

    static String legibleOnWhite(String primaryHex, List<VisualIdentityOutput.ColorSwatch> palette,
                                 double minRatio, Fallback fallback) {
        if (contrastWithWhite(channelsOf(primaryHex)) >= minRatio) return primaryHex;
        String chosen = null;
        double bestMargin = fallback == Fallback.DEEPEST ? -1 : Double.MAX_VALUE;
        if (palette != null) {
            for (VisualIdentityOutput.ColorSwatch s : palette) {
                double margin = contrastWithWhite(channelsOf(s.hex())) - minRatio;
                if (margin < 0) continue;
                boolean better = fallback == Fallback.DEEPEST ? margin > bestMargin : margin < bestMargin;
                if (better) { bestMargin = margin; chosen = s.hex(); }
            }
        }
        return chosen != null ? chosen : inkOnWhite(primaryHex, minRatio);
    }

    private static double[] channelsOf(String hex) {
        int[] rgb = parseHex(hex);
        return new double[]{rgb[0], rgb[1], rgb[2]};
    }

    /** The primary stepped toward black until it carries body type. Last resort of {@link #legibleOnWhite}. */
    static String inkOnWhite(String hex) {
        return inkOnWhite(hex, MIN_TEXT_CONTRAST);
    }

    /** The primary stepped toward black until it clears {@code minRatio} against white. */
    static String inkOnWhite(String hex, double minRatio) {
        int[] rgb = parseHex(hex);
        double[] c = {rgb[0], rgb[1], rgb[2]};
        // 60 steps of 2.5% take any colour to black, where contrast is 21:1. The test is on the
        // rounded channels, because those are the ones the page actually paints — stopping on the
        // unrounded value lands a hair under the threshold.
        for (int i = 0; i < 60 && contrastWithWhite(rounded(c)) < minRatio; i++) {
            for (int ch = 0; ch < 3; ch++) c[ch] *= 0.975;
        }
        double[] out = rounded(c);
        return String.format("#%02x%02x%02x", (int) out[0], (int) out[1], (int) out[2]);
    }

    private static double[] rounded(double[] c) {
        return new double[]{Math.round(c[0]), Math.round(c[1]), Math.round(c[2])};
    }

    private static double contrastWithWhite(double[] rgb) {
        return 1.05 / (relativeLuminance(rgb) + 0.05);
    }

    /** WCAG 2.1 relative luminance. */
    private static double relativeLuminance(double[] rgb) {
        double[] lin = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = rgb[i] / 255.0;
            lin[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
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
