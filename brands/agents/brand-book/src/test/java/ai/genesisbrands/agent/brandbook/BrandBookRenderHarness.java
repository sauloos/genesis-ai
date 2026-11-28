package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.PhotoSourcingService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Not a test: a way to look at real books while changing the templates. Skipped unless
 * {@code BB_HARNESS_DIR} names a folder of {@code *.json} files, each {@code {"template":
 * "template-ember.html", "input": BrandBookInput, "output": BrandBookOutput}}. For each one it
 * writes the built HTML, the PDF and a PNG of every page into {@code BB_HARNESS_DIR/out}.
 * Blobs (logos, photos) are read from {@code BB_BLOB_ROOT}. With {@code BB_PHOTOS=1} (and
 * {@code UNSPLASH_ACCESS_KEY}) each book first gets its direction's photos through the real
 * {@link PhotoSourcingService}, written under {@code BB_BLOB_ROOT}; a direction that has them keeps them,
 * unless {@code BB_PHOTOS=refetch}, which replaces them.
 */
class BrandBookRenderHarness {

    @Test
    void renderEveryBookInTheHarnessFolder() throws Exception {
        String dirEnv = System.getenv("BB_HARNESS_DIR");
        assumeTrue(dirEnv != null && !dirEnv.isBlank(), "BB_HARNESS_DIR not set");
        Path dir = Path.of(dirEnv);
        Path blobRoot = Path.of(System.getenv().getOrDefault("BB_BLOB_ROOT", "data/blobs"));
        Path out = dir.resolve("out");
        Files.createDirectories(out);

        BlobStorageService blobs = new BlobStorageService() {
            @Override
            public byte[] download(String blobPath) throws IOException {
                return Files.readAllBytes(blobRoot.resolve(blobPath));
            }

            @Override
            public void upload(String blobPath, byte[] data) throws IOException {
                Path target = blobRoot.resolve(blobPath);
                Files.createDirectories(target.getParent());
                Files.write(target, data);
            }

            @Override
            public void delete(String blobPath) {
                try {
                    Files.deleteIfExists(blobRoot.resolve(blobPath));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
        BrandBookTemplateRenderer renderer = new BrandBookTemplateRenderer(blobs, null, null, 1);
        renderer.init();
        ObjectMapper om = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String only = System.getenv("BB_ONLY");
        PhotoSourcingService photos = null;
        String photoMode = System.getenv().getOrDefault("BB_PHOTOS", "");
        if (photoMode.equals("1") || photoMode.equals("refetch")) {
            photos = new PhotoSourcingService(blobs, new ObjectMapper());
            Field key = PhotoSourcingService.class.getDeclaredField("unsplashAccessKey");
            key.setAccessible(true);
            key.set(photos, System.getenv("UNSPLASH_ACCESS_KEY"));
        }
        try (Playwright pw = Playwright.create(); Stream<Path> files = Files.list(dir)) {
            Browser browser = pw.chromium().launch();
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = f.getFileName().toString().replace(".json", "");
                if (only != null && !only.isBlank() && !name.contains(only)) continue;
                JsonNode root = om.readTree(f.toFile());
                if (!root.has("input") || !root.has("output")) continue;
                BrandBookInput input = om.treeToValue(root.get("input"), BrandBookInput.class);
                BrandBookOutput output = om.treeToValue(root.get("output"), BrandBookOutput.class);
                if (photos != null) {
                    String eng = input.brief().engagementId(), d = input.brief().direction().name();
                    List<String> kw = input.visualIdentity().imageKeywords();
                    var stored = photoMode.equals("refetch") ? photos.fetchAndStore(eng, d, kw) : photos.ensurePhotos(eng, d, kw);
                    System.out.println("HARNESS " + name + ": photos " + stored.keySet());
                }
                String template = root.path("template").asText("template-ember.html");
                String templateHtml;
                try (InputStream in = getClass().getResourceAsStream("/templates/brand-book/" + template)) {
                    templateHtml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if ("1".equals(System.getenv("BB_PREVIEW"))) {
                    var pv = renderer.renderPreviewPages(input, output, templateHtml, Set.of());
                    for (int i = 0; i < pv.size(); i++) {
                        Files.write(out.resolve("%s-pv-%02d.jpg".formatted(name, i + 1)), pv.get(i).jpeg());
                    }
                    System.out.println("HARNESS " + name + " preview pages: "
                        + pv.stream().map(BrandBookTemplateRenderer.PreviewPage::chapter).toList());
                }
                String html = renderer.buildHtml(input, output, templateHtml, Set.of());
                Path htmlFile = out.resolve(name + ".html");
                Files.writeString(htmlFile, html);
                if (!"0".equals(System.getenv("BB_PDF"))) {
                    Files.write(out.resolve(name + ".pdf"), renderer.renderHtml(html, false));
                }
                try (BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(900, 1300).setDeviceScaleFactor(1))) {
                    Page tab = ctx.newPage();
                    tab.navigate(htmlFile.toUri().toString(),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                    tab.evaluate("document.fonts.ready.then(() => true)");
                    Locator pages = tab.locator(".page");
                    int n = pages.count();
                    for (int i = 0; i < n; i++) {
                        pages.nth(i).screenshot(new Locator.ScreenshotOptions()
                            .setPath(out.resolve("%s-%02d.png".formatted(name, i + 1))));
                    }
                    System.out.println("HARNESS " + name + ": " + n + " pages, fonts="
                        + tab.evaluate("[...document.fonts].filter(f => f.status === 'loaded').map(f => f.family + ' ' + f.weight).join(', ')"));
                }
            }
            browser.close();
        } finally {
            renderer.destroy();
        }
    }
}
