package ai.genesisbrands.previews;

import ai.genesisbrands.security.SignedTokens;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.DocumentPreviewProvider;
import ai.genesisbrands.service.DocumentPreviewProvider.Access;
import ai.genesisbrands.service.DocumentPreviewProvider.Document;
import ai.genesisbrands.service.DocumentPreviewProvider.PageSpec;
import ai.genesisbrands.service.DocumentPreviewProvider.RenderedPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The mechanics of a view-only preview. A client asks for a document's manifest and receives
 * the page sequence, what each page is, and a signed expiring link for every page it may see;
 * each link serves one watermarked rendition and is checked against the signed-in client
 * again, so a copied link is useless to anyone else. Page images are rendered once per content
 * version by the tenant and kept in private storage; renditions are built per request and
 * never stored. Every rule here fails closed.
 */
public class DocumentPreviewService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPreviewService.class);
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Tunables; every one has a safe default and a {@code genesis.previews.*} property. */
    public record Settings(Duration tokenTtl, int manifestsPerMinute, int pagesPerMinute,
                           int pagesPerDay, int pagesPerIpPerMinute, int maxWidth) {
        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(5), 10, 90, 1500, 240, 1200);
        }
    }

    public record ManifestPage(int index, String chapter, String access, String label, String description, String url) {}

    public record Manifest(String title, String variantKey, String variantLabel, String viewCode,
                           int expiresInSeconds, List<ManifestPage> pages) {}

    /** A rendered page and the filename-free headers' worth of facts the controller needs. */
    public record Rendition(byte[] jpeg) {}

    private final DocumentPreviewProvider provider;
    private final BlobStorageService blobs;
    private final SignedTokens tokens;
    private final PreviewViewLogRepository audit;
    private final PreviewRateLimiter limiter;
    private final ObjectMapper mapper;
    private final Settings settings;
    private final Clock clock;
    private final Map<String, Object> renderLocks = new ConcurrentHashMap<>();
    private final Semaphore renders = new Semaphore(1, true);

    public DocumentPreviewService(DocumentPreviewProvider provider, BlobStorageService blobs, SignedTokens tokens,
                                  PreviewViewLogRepository audit, PreviewRateLimiter limiter, ObjectMapper mapper,
                                  Settings settings, Clock clock) {
        this.provider = provider;
        this.blobs = blobs;
        this.tokens = tokens;
        this.audit = audit;
        this.limiter = limiter;
        this.mapper = mapper;
        this.settings = settings;
        this.clock = clock;
    }

    public List<Document> list(String clientUserId) {
        requireReady();
        return provider.listPreviewable(clientUserId);
    }

    public Manifest manifest(String clientUserId, String workItemId, String variantKey, String ip, String userAgent) {
        requireReady();
        if (!limiter.allow("m:" + clientUserId, settings.manifestsPerMinute(), Duration.ofMinutes(1))) throw tooMany();
        Document doc = requirePreviewable(workItemId, variantKey, clientUserId);
        List<String> chapters = ensureBasePages(doc, clientUserId);
        List<PageSpec> specs = provider.curate(doc, chapters);
        String viewCode = newViewCode();
        Instant expires = clock.instant().plus(settings.tokenTtl());

        List<ManifestPage> pages = new ArrayList<>();
        for (PageSpec s : specs) {
            String url = null;
            if (s.access() != Access.WITHHOLD && s.index() >= 0 && s.index() < chapters.size()) {
                url = "/api/client/previews/p/" + tokens.sign(String.join("|", clientUserId, doc.workItemId(),
                    doc.variantKey(), doc.contentVersion(), String.valueOf(s.index()), s.access().name(), viewCode), expires);
            }
            pages.add(new ManifestPage(s.index(), s.chapter(), s.access().name(), s.label(), s.description(), url));
        }
        recordView(viewCode, clientUserId, doc, PreviewViewLog.MANIFEST, null, ip, userAgent);
        return new Manifest(doc.title(), doc.variantKey(), doc.variantLabel(), viewCode,
            (int) settings.tokenTtl().toSeconds(), pages);
    }

    /** One watermarked page for the signed-in client, or an error: never an unmarked or foreign page. */
    public Rendition page(String token, String clientUserId, String identity, String ip, String userAgent) {
        requireReady();
        String payload = tokens.verify(token, clock.instant())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This preview link has expired"));
        String[] p = payload.split("\\|", -1);
        if (p.length != 7) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown page");
        if (!p[0].equals(clientUserId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your preview");
        if (!limiter.allow("p:" + clientUserId, settings.pagesPerMinute(), Duration.ofMinutes(1))
            || !limiter.allow("pd:" + clientUserId + ":" + p[1], settings.pagesPerDay(), Duration.ofDays(1))
            || !limiter.allow("pi:" + ip, settings.pagesPerIpPerMinute(), Duration.ofMinutes(1))) throw tooMany();

        Document doc = requirePreviewable(p[1], p[2], clientUserId);
        if (!doc.contentVersion().equals(p[3])) {
            throw new ResponseStatusException(HttpStatus.GONE, "This document has changed; reopen the preview");
        }
        int index;
        Access access;
        try {
            index = Integer.parseInt(p[4]);
            access = Access.valueOf(p[5]);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown page");
        }
        if (access == Access.WITHHOLD) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Page withheld");

        byte[] base;
        try {
            base = blobs.download(pagePath(doc, index));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not available");
        }
        try {
            byte[] jpeg = PageCompositor.compose(base, access, identity, p[6],
                LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString(), settings.maxWidth());
            recordView(p[6], clientUserId, doc, PreviewViewLog.PAGE, index, ip, userAgent);
            return new Rendition(jpeg);
        } catch (IOException | RuntimeException e) {
            log.error("Preview rendition failed for {}/{} page {}: {}", doc.workItemId(), doc.variantKey(), index, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Preview unavailable");
        }
    }

    /** The single gate every path goes through: the provider must say this client may preview this document. */
    Document requirePreviewable(String workItemId, String variantKey, String clientUserId) {
        return provider.find(workItemId, variantKey, clientUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such preview"));
    }

    // ── stored base pages ─────────────────────────────────────────────────────

    private static String prefix(Document d) {
        return "previews/pages/%s/%s/%s".formatted(safe(d.workItemId()), safe(d.variantKey()), safe(d.contentVersion()));
    }

    private static String pagePath(Document d, int index) {
        return prefix(d) + "/" + index + ".jpg";
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private List<String> readIndex(Document d) {
        try {
            return mapper.readValue(blobs.download(prefix(d) + "/index.json"), new TypeReference<List<String>>() {});
        } catch (Exception missing) {
            return null;
        }
    }

    /** Renders the document's pages once per content version; concurrent callers share one render. */
    private List<String> ensureBasePages(Document doc, String clientUserId) {
        List<String> chapters = readIndex(doc);
        if (chapters != null) return chapters;
        Object lock = renderLocks.computeIfAbsent(prefix(doc), k -> new Object());
        try {
            synchronized (lock) {
                chapters = readIndex(doc);
                if (chapters != null) return chapters;
                renders.acquireUninterruptibly();
                try {
                    List<RenderedPage> pages = provider.renderPages(doc, clientUserId);
                    List<String> out = new ArrayList<>();
                    for (int i = 0; i < pages.size(); i++) {
                        blobs.upload(pagePath(doc, i), pages.get(i).image());
                        out.add(pages.get(i).chapter() == null ? "" : pages.get(i).chapter());
                    }
                    blobs.upload(prefix(doc) + "/index.json", mapper.writeValueAsBytes(out));  // last: marks the set complete
                    return out;
                } catch (IOException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Preview unavailable");
                } finally {
                    renders.release();
                }
            }
        } finally {
            renderLocks.remove(prefix(doc), lock);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void recordView(String viewCode, String clientUserId, Document doc, String event, Integer page, String ip, String ua) {
        try {
            PreviewViewLog row = new PreviewViewLog();
            row.setId(UUID.randomUUID().toString());
            row.setViewCode(viewCode);
            row.setClientUserId(clientUserId);
            row.setWorkItemId(doc.workItemId());
            row.setVariantKey(doc.variantKey());
            row.setEvent(event);
            row.setPageIndex(page);
            row.setIp(ip == null ? null : ip.substring(0, Math.min(64, ip.length())));
            row.setUserAgent(ua == null ? null : ua.substring(0, Math.min(200, ua.length())));
            row.setCreatedAt(clock.instant());
            audit.save(row);
        } catch (RuntimeException e) {
            // The audit trail must not take previews down, but its loss is loud.
            log.error("Could not record preview view {}: {}", viewCode, e.toString());
        }
    }

    private void requireReady() {
        if (!tokens.ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Previews are not configured");
    }

    private static ResponseStatusException tooMany() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many preview requests; try again shortly");
    }

    private static String newViewCode() {
        char[] c = new char[8];
        for (int i = 0; i < c.length; i++) c[i] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
        return new String(c);
    }

    /** "sa***@gmail.com": enough to tell whose viewing a leaked page came from, without printing a full address. */
    public static String maskIdentity(String email) {
        if (email == null || email.isBlank()) return "viewer";
        int at = email.indexOf('@');
        if (at <= 0) return "viewer";
        String local = email.substring(0, at);
        return local.substring(0, Math.min(2, local.length())) + "***" + email.substring(at);
    }
}
