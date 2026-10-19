package ai.genesisbrands.agent.brandbook;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The dashboard asks for several direction previews at once, so the renderer is called from
 * many request threads together. Playwright for Java is single-threaded per instance; sharing
 * one Browser across threads failed with "Object doesn't exist" on prod.
 */
class BrandBookTemplateRendererConcurrencyTest {

    private static final String HTML = """
        <html><head><style>@page { size: A4; margin: 0; } .page { width: 210mm; height: 297mm; }</style></head>
        <body><div class="page"><h1>Concurrency</h1></div></body></html>""";

    private static BrandBookTemplateRenderer renderer;

    @BeforeAll
    static void start() {
        assumeTrue(chromiumAvailable(), "Playwright Chromium is not installed");
        renderer = new BrandBookTemplateRenderer(null, null, null, 2);
        renderer.init();
    }

    @AfterAll
    static void stop() {
        if (renderer != null) renderer.destroy();
    }

    @Test
    void rendersPdfsAndPreviewsFromManyThreadsAtOnce() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Callable<byte[]>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                calls.add(i % 2 == 0 ? () -> renderer.renderHtml(HTML, false) : () -> renderer.renderPreviewScreenshot(HTML));
            }
            for (Future<byte[]> f : callers.invokeAll(calls)) {
                assertTrue(f.get().length > 0);
            }
        } finally {
            callers.shutdownNow();
        }
    }

    private static boolean chromiumAvailable() {
        try (Playwright p = Playwright.create()) {
            p.chromium().launch().close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
