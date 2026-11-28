package ai.genesisbrands.previews;

import ai.genesisbrands.security.SignedTokens;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.DocumentPreviewProvider;
import ai.genesisbrands.service.DocumentPreviewProvider.Access;
import ai.genesisbrands.service.DocumentPreviewProvider.Document;
import ai.genesisbrands.service.DocumentPreviewProvider.PageSpec;
import ai.genesisbrands.service.DocumentPreviewProvider.RenderedPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The security-bearing behaviour of a preview: who gets which page, and what never leaves. */
class DocumentPreviewServiceTest {

    private final Map<String, byte[]> store = new HashMap<>();
    private final BlobStorageService blobs = mock(BlobStorageService.class);
    private final PreviewViewLogRepository audit = mock(PreviewViewLogRepository.class);
    private final AtomicInteger renderCalls = new AtomicInteger();
    private Optional<Document> findResult;
    private String version = "v1";
    private DocumentPreviewService service;
    private Instant now = Instant.parse("2026-11-28T10:00:00Z");

    private final DocumentPreviewProvider provider = new DocumentPreviewProvider() {
        public List<Document> listPreviewable(String c) { return findResult.stream().toList(); }
        public Optional<Document> find(String w, String v, String c) {
            return findResult.filter(d -> d.workItemId().equals(w) && d.variantKey().equals(v));
        }
        public List<RenderedPage> renderPages(Document d, String c) {
            renderCalls.incrementAndGet();
            try {
                byte[] img = PageCompositorTest.page(900, 1200);
                return List.of(new RenderedPage("Cover", img), new RenderedPage("Logo System", img), new RenderedPage("Typography", img));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        public List<PageSpec> curate(Document d, List<String> chapters) {
            return List.of(
                new PageSpec(0, "Cover", Access.SHOW, "Cover", "The cover"),
                new PageSpec(1, "Logo System", Access.BLUR, "Logo", "Your logo"),
                new PageSpec(2, "Typography", Access.WITHHOLD, "Typography", "Fonts and sizes, not shown in the preview"));
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("java.awt.headless", "true");
        findResult = Optional.of(new Document("e1", "EVOLVED", "Evolved", "One Way", version));
        doAnswer(i -> { store.put(i.getArgument(0), i.getArgument(1)); return null; })
            .when(blobs).upload(anyString(), any(byte[].class));
        when(blobs.download(anyString())).thenAnswer(i -> {
            byte[] b = store.get(i.<String>getArgument(0));
            if (b == null) throw new IOException("missing");
            return b;
        });
        service = build(DocumentPreviewService.Settings.defaults());
    }

    private DocumentPreviewService build(DocumentPreviewService.Settings s) {
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now; }
        };
        return new DocumentPreviewService(provider, blobs, new SignedTokens("a-long-enough-test-secret"), audit,
            new PreviewRateLimiter(), new ObjectMapper(), s, clock);
    }

    private static String tokenOf(DocumentPreviewService.ManifestPage p) {
        return p.url().substring("/api/client/previews/p/".length());
    }

    @Test
    void manifestRendersOncePerVersionAndLinksOnlyViewablePages() {
        var m1 = service.manifest("u1", "e1", "EVOLVED", "1.2.3.4", "ua");
        service.manifest("u1", "e1", "EVOLVED", "1.2.3.4", "ua");

        assertThat(renderCalls.get()).isEqualTo(1);
        assertThat(m1.pages()).extracting(DocumentPreviewService.ManifestPage::access)
            .containsExactly("SHOW", "BLUR", "WITHHOLD");
        assertThat(m1.pages().get(0).url()).startsWith("/api/client/previews/p/");
        assertThat(m1.pages().get(2).url()).isNull();                        // withheld: no link at all
        assertThat(m1.pages().get(2).description()).contains("not shown");   // but the viewer is told what it holds
        assertThat(m1.viewCode()).hasSize(8);
        verify(audit, times(2)).save(any(PreviewViewLog.class));
    }

    @Test
    void aClientWhoMayNotPreviewTheDocumentGetsNotFound() {
        findResult = Optional.empty();
        assertThatThrownBy(() -> service.manifest("intruder", "e1", "EVOLVED", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(renderCalls.get()).isZero();
    }

    @Test
    void aPageIsServedWatermarkedToTheClientItWasIssuedTo() throws Exception {
        var m = service.manifest("u1", "e1", "EVOLVED", "ip", "ua");
        byte[] jpeg = service.page(tokenOf(m.pages().get(0)), "u1", "sa***@x.com", "ip", "ua").jpeg();

        assertThat(jpeg).isNotEmpty();
        assertThat(jpeg).isNotEqualTo(store.get("previews/pages/e1/EVOLVED/v1/0.jpg"));  // never the stored base
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg)).getWidth()).isEqualTo(900);
    }

    @Test
    void anotherClientsLinkIsForbidden() {
        var m = service.manifest("u1", "e1", "EVOLVED", "ip", "ua");
        assertThatThrownBy(() -> service.page(tokenOf(m.pages().get(0)), "u2", "x", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void anExpiredOrForgedLinkIsGone() {
        var m = service.manifest("u1", "e1", "EVOLVED", "ip", "ua");
        String token = tokenOf(m.pages().get(0));
        now = now.plus(Duration.ofMinutes(6));
        assertThatThrownBy(() -> service.page(token, "u1", "x", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.GONE));
        assertThatThrownBy(() -> service.page("forged.token.value", "u1", "x", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    @Test
    void accessIsRecheckedOnEveryPageRequest() {
        var m = service.manifest("u1", "e1", "EVOLVED", "ip", "ua");
        findResult = Optional.empty();   // e.g. the engagement stopped being previewable
        assertThatThrownBy(() -> service.page(tokenOf(m.pages().get(0)), "u1", "x", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aChangedDocumentInvalidatesOldLinks() {
        var m = service.manifest("u1", "e1", "EVOLVED", "ip", "ua");
        findResult = Optional.of(new Document("e1", "EVOLVED", "Evolved", "One Way", "v2"));
        assertThatThrownBy(() -> service.page(tokenOf(m.pages().get(0)), "u1", "x", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    @Test
    void aCorruptStoredPageFailsClosedInsteadOfServingBytes() {
        var m = service.manifest("u1", "e1", "EVOLVED", "ip", "ua");
        store.put("previews/pages/e1/EVOLVED/v1/0.jpg", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.page(tokenOf(m.pages().get(0)), "u1", "x", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void pageRequestsAreRateLimitedPerClient() {
        service = build(new DocumentPreviewService.Settings(Duration.ofMinutes(5), 10, 2, 1500, 240, 1200));
        var m = service.manifest("u1", "e1", "EVOLVED", "ip", "ua");
        String t = tokenOf(m.pages().get(0));
        service.page(t, "u1", "x", "ip", "ua");
        service.page(t, "u1", "x", "ip", "ua");
        assertThatThrownBy(() -> service.page(t, "u1", "x", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void withoutASigningKeyNothingIsServed() {
        DocumentPreviewService off = new DocumentPreviewService(provider, blobs, new SignedTokens(""), audit,
            new PreviewRateLimiter(), new ObjectMapper(), DocumentPreviewService.Settings.defaults(), Clock.systemUTC());
        assertThatThrownBy(() -> off.manifest("u1", "e1", "EVOLVED", "ip", "ua"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void identitiesAreMasked() {
        assertThat(DocumentPreviewService.maskIdentity("saulo@gmail.com")).isEqualTo("sa***@gmail.com");
        assertThat(DocumentPreviewService.maskIdentity("a@x.com")).isEqualTo("a***@x.com");
        assertThat(DocumentPreviewService.maskIdentity(null)).isEqualTo("viewer");
    }
}
