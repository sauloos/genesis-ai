package ai.genesisbrands.service;

import ai.genesisbrands.agent.brandbook.BrandBookInput;
import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.service.EngagementOrchestratorService.DirectionOutput;
import ai.genesisbrands.service.EngagementOrchestratorService.EngagementResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Genesis Brands' answer to "what may a client look inside": the brand book of each direction
 * of the client's own finished engagement. Core ({@code os/previews}) does the serving; this
 * supplies the documents, renders the book to page images (colour specifications redacted by the
 * renderer), and decides which pages a preview shows. The default curation follows the founder's
 * model: the opening pages, the logo, a taste of imagery and applications, the closing page; the
 * rest is withheld, and the viewer is told what each withheld page holds.
 */
@Component
@RequiredArgsConstructor
public class EngagementPreviewDocuments implements DocumentPreviewProvider {

    private static final Logger log = LoggerFactory.getLogger(EngagementPreviewDocuments.class);

    /** Bump when the page rendering changes, so stored page images are rebuilt. */
    static final String RENDER_VERSION = "r1";
    static final String DOC_TITLE_SUFFIX = " brand book";

    private static final Map<String, String> CHAPTER_CONTENT = Map.ofEntries(
        Map.entry("Brand Statement", "Who you are and why you exist, in your own words"),
        Map.entry("Mission", "Your mission statement"),
        Map.entry("Vision", "The future your brand is working towards"),
        Map.entry("Values", "The values your brand lives by"),
        Map.entry("Logo System", "Your logo in full: clear space, minimum sizes and colour versions"),
        Map.entry("Brand Colours", "Your full palette with exact HEX, RGB, CMYK and Pantone values"),
        Map.entry("Gradients", "Approved gradients and how to use them"),
        Map.entry("Graphic Devices", "The shapes and patterns that make your brand recognisable"),
        Map.entry("Photography", "The photographic style, with examples"),
        Map.entry("Typography", "Your typefaces, sizes and hierarchy"),
        Map.entry("Layout & Grids", "The grid and layout rules for every format"),
        Map.entry("Applications", "Your brand in use: cards, signage, social and more"),
        Map.entry("Stationery", "Letterheads, cards and printed essentials"),
        Map.entry("Brand Language", "The words and phrases that sound like you"),
        Map.entry("Tone of Voice", "How your brand speaks, with do and don't examples"),
        Map.entry("Closing", "The closing page"));

    /** Chapters of which the first page is part of the preview. */
    private static final Set<String> TASTE_CHAPTERS = Set.of("Photography", "Applications");

    private final EngagementRepository engagements;
    private final ObjectMapper mapper;
    private final BrandBookTemplateRenderer renderer;

    @Override
    public List<Document> listPreviewable(String clientUserId) {
        List<Document> out = new ArrayList<>();
        for (Engagement e : engagements.findAllByClientUserIdOrderByCreatedAtDesc(clientUserId)) {
            if (e.getStatus() != Engagement.Status.DONE) continue;
            EngagementResults results = parse(e);
            if (results == null || results.directions() == null) continue;
            for (DirectionOutput d : results.directions()) {
                toDocument(e, d).ifPresent(out::add);
            }
        }
        return out;
    }

    @Override
    public Optional<Document> find(String workItemId, String variantKey, String clientUserId) {
        Engagement e = engagements.findById(workItemId).orElse(null);
        if (e == null || e.getClientUserId() == null || !e.getClientUserId().equals(clientUserId)) return Optional.empty();
        if (e.getStatus() != Engagement.Status.DONE) return Optional.empty();
        DirectionOutput d = direction(e, variantKey);
        return d == null ? Optional.empty() : toDocument(e, d);
    }

    @Override
    public List<RenderedPage> renderPages(Document document, String clientUserId) {
        Engagement e = engagements.findById(document.workItemId()).orElseThrow();
        if (!clientUserId.equals(e.getClientUserId())) throw new IllegalStateException("Not this client's engagement");
        DirectionOutput d = direction(e, document.variantKey());
        if (d == null || d.brandBook() == null) throw new IllegalStateException("No brand book to preview");
        BrandBookInput input = new BrandBookInput(d.brief(), d.playbook(), d.copy(), d.visualIdentity(), d.logo());
        return renderer.renderPreviewPages(input, d.brandBook()).stream()
            .map(p -> new RenderedPage(p.chapter(), p.jpeg()))
            .toList();
    }

    @Override
    public List<PageSpec> curate(Document document, List<String> chapters) {
        return curateChapters(chapters);
    }

    /** The default preview policy, from nothing but each page's chapter. */
    static List<PageSpec> curateChapters(List<String> chapters) {
        int n = chapters.size();
        Set<Integer> shown = new java.util.HashSet<>();
        for (int i = 0; i < Math.min(3, n); i++) shown.add(i);
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            String c = chapters.get(i);
            boolean first = seen.add(c);
            if (first && TASTE_CHAPTERS.contains(c)) shown.add(i);
            if (first && "Logo System".equals(c)) {                    // the logo page, and the one after it
                shown.add(i);
                if (i + 1 < n && "Logo System".equals(chapters.get(i + 1))) shown.add(i + 1);
            }
        }
        if (n > 0) shown.add(n - 1);

        List<PageSpec> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String chapter = chapters.get(i);
            String label = "Page " + (i + 1) + (chapter.isBlank() ? "" : " · " + chapter);
            boolean show = shown.contains(i);
            String what = CHAPTER_CONTENT.getOrDefault(chapter, chapter.isBlank() ? "More of your brand book" : chapter);
            out.add(new PageSpec(i, chapter, show ? Access.SHOW : Access.WITHHOLD, label,
                show ? what : what + ". Not shown in the preview."));
        }
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Optional<Document> toDocument(Engagement e, DirectionOutput d) {
        if (d.brandBook() == null || d.direction() == null) return Optional.empty();
        String name = d.brief() != null && d.brief().brand() != null && d.brief().brand().name() != null
            ? d.brief().brand().name() : "Your";
        String key = d.direction().toUpperCase();
        String label = key.charAt(0) + key.substring(1).toLowerCase();
        try {
            return Optional.of(new Document(e.getId(), key, label, name + DOC_TITLE_SUFFIX, contentVersion(d)));
        } catch (Exception ex) {
            log.warn("Could not version preview for engagement {} direction {}: {}", e.getId(), key, ex.toString());
            return Optional.empty();
        }
    }

    private static String contentVersion(DirectionOutput d, ObjectMapper m) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(m.writeValueAsBytes(d));
        return HexFormat.of().formatHex(digest, 0, 8) + "-" + RENDER_VERSION;
    }

    private String contentVersion(DirectionOutput d) throws Exception {
        return contentVersion(d, mapper);
    }

    private DirectionOutput direction(Engagement e, String variantKey) {
        EngagementResults results = parse(e);
        if (results == null || results.directions() == null) return null;
        return results.directions().stream()
            .filter(d -> d.direction() != null && d.direction().equalsIgnoreCase(variantKey))
            .findFirst().orElse(null);
    }

    private EngagementResults parse(Engagement e) {
        if (e.getResultsJson() == null || e.getResultsJson().isBlank()) return null;
        try {
            return mapper.readValue(e.getResultsJson(), EngagementResults.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
