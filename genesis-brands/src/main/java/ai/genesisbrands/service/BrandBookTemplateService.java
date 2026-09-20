package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.model.BrandBookTemplate;
import ai.genesisbrands.repository.BrandBookTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BrandBookTemplateService {

    private static final Logger log = LoggerFactory.getLogger(BrandBookTemplateService.class);

    private final BrandBookTemplateRepository templateRepo;
    private final ObjectMapper objectMapper;

    // In-memory cache: templateId → resolved HTML string
    private final Map<String, String> htmlCache = new ConcurrentHashMap<>();

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    @PostConstruct
    void seed() {
        boolean hasMeridian = templateRepo.findAll().stream().anyMatch(t -> "Meridian".equals(t.getName()));
        boolean hasEmber    = templateRepo.findAll().stream().anyMatch(t -> "Ember".equals(t.getName()));

        if (templateRepo.count() == 0) {
            BrandBookTemplate classic = new BrandBookTemplate();
            classic.setId(UUID.randomUUID().toString());
            classic.setName("Classic");
            classic.setDescription(
                "The original Genesis Brands brand book template. 26-page A4 portrait format. " +
                "Covers all core identity sections plus photography, stationery, and vocabulary.");
            classic.setStatus(BrandBookTemplate.Status.DEPRECATED);
            classic.setClasspathPath("templates/brand-book/template-classic.html");
            classic.setSectionManifestJson(CLASSIC_MANIFEST);
            classic.setSelectionHintsJson("""
                {"format":"PORTRAIT","suitableFor":["all"],"archetypes":["ANCHORED","EVOLVED","DISRUPTIVE"],"selectionWeight":0}
                """.strip());
            templateRepo.save(classic);
            log.info("BrandBookTemplateService: seeded Classic (deprecated)");
        }

        if (!hasMeridian) {
            BrandBookTemplate meridian = new BrandBookTemplate();
            meridian.setId(UUID.randomUUID().toString());
            meridian.setName("Meridian");
            meridian.setDescription(
                "Landscape A4 format with a structured left sidebar and horizontal grid layout. " +
                "Clean and corporate — suited to B2B, professional services, and tech brands.");
            meridian.setStatus(BrandBookTemplate.Status.CURRENT);
            meridian.setClasspathPath("templates/brand-book/template-meridian.html");
            meridian.setSectionManifestJson(MERIDIAN_MANIFEST);
            meridian.setSelectionHintsJson("""
                {"format":"LANDSCAPE","suitableFor":["b2b","tech","finance","professional-services"],"archetypes":["ANCHORED","EVOLVED"],"selectionWeight":5}
                """.strip());
            templateRepo.save(meridian);
            log.info("BrandBookTemplateService: seeded Meridian (current)");
        }

        if (!hasEmber) {
            BrandBookTemplate ember = new BrandBookTemplate();
            ember.setId(UUID.randomUUID().toString());
            ember.setName("Ember");
            ember.setDescription(
                "Portrait A4 format with expressive section openers, bold typography, and warm layouts. " +
                "Suited to consumer, lifestyle, wellness, and creative brands.");
            ember.setStatus(BrandBookTemplate.Status.CURRENT);
            ember.setClasspathPath("templates/brand-book/template-ember.html");
            ember.setSectionManifestJson(EMBER_MANIFEST);
            ember.setSelectionHintsJson("""
                {"format":"PORTRAIT","suitableFor":["consumer","lifestyle","wellness","creative","retail"],"archetypes":["EVOLVED","DISRUPTIVE"],"selectionWeight":5}
                """.strip());
            templateRepo.save(ember);
            log.info("BrandBookTemplateService: seeded Ember (current)");
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<BrandBookTemplate> listAll() {
        return templateRepo.findAllByOrderByCreatedAtDesc();
    }

    public BrandBookTemplate get(String id) {
        return templateRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
    }

    /**
     * Load the HTML for a template (cached after first load).
     */
    public String loadHtml(BrandBookTemplate template) {
        return htmlCache.computeIfAbsent(template.getId(), k -> {
            try {
                String html = new ClassPathResource(template.getClasspathPath())
                    .getContentAsString(StandardCharsets.UTF_8);
                log.info("BrandBookTemplateService: loaded '{}' HTML ({} bytes) from classpath:{}",
                    template.getName(), html.length(), template.getClasspathPath());
                return html;
            } catch (IOException e) {
                throw new RuntimeException("Cannot load template HTML: " + template.getClasspathPath(), e);
            }
        });
    }

    /**
     * Orchestrator entry point: pick the best CURRENT template for a given brand
     * context. Falls back to any CURRENT template, then to any DEPRECATED one.
     *
     * Selection is currently rule-based scoring. When we have multiple current
     * templates this will grow to evaluate industry/archetype hints from selectionHintsJson.
     */
    public BrandBookTemplate select(DirectionBrief.BrandContext brand, DirectionBrief.CreativeDirection direction) {
        List<BrandBookTemplate> current = templateRepo.findByStatusOrderByCreatedAtDesc(BrandBookTemplate.Status.CURRENT);
        if (current.isEmpty()) {
            log.warn("No CURRENT brand book templates found — falling back to any DEPRECATED");
            return templateRepo.findByStatusOrderByCreatedAtDesc(BrandBookTemplate.Status.DEPRECATED)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No brand book templates configured"));
        }

        // Guard: only consider templates whose HTML is actually in this deployment's classpath.
        // If the container hasn't been rebuilt after new templates were added, those entries will
        // be in the DB but their files won't be in the JAR — skip them rather than 500.
        List<BrandBookTemplate> loadable = current.stream()
            .filter(t -> new ClassPathResource(t.getClasspathPath()).exists())
            .collect(Collectors.toList());
        if (loadable.isEmpty()) {
            log.warn("No CURRENT templates have loadable HTML (JAR may be stale) — falling back to DEPRECATED");
            return templateRepo.findByStatusOrderByCreatedAtDesc(BrandBookTemplate.Status.DEPRECATED)
                .stream().filter(t -> new ClassPathResource(t.getClasspathPath()).exists())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No brand book templates have loadable HTML"));
        }
        if (loadable.size() < current.size()) {
            log.warn("BrandBookTemplateService: {} of {} CURRENT templates are missing from classpath and will be skipped",
                current.size() - loadable.size(), current.size());
        }
        current = loadable;

        if (current.size() == 1) {
            log.info("BrandBookTemplateService: single CURRENT template '{}' selected", current.get(0).getName());
            return current.get(0);
        }

        // Score each template against the brand context
        BrandBookTemplate selected = current.stream()
            .max((a, b) -> score(a, brand, direction) - score(b, brand, direction))
            .orElse(current.get(0));
        log.info("BrandBookTemplateService: selected '{}' (direction={}, scores={})",
            selected.getName(), direction,
            current.stream().map(t -> t.getName() + ":" + score(t, brand, direction))
                .collect(java.util.stream.Collectors.joining(", ")));
        return selected;
    }

    private int score(BrandBookTemplate t, DirectionBrief.BrandContext brand, DirectionBrief.CreativeDirection direction) {
        if (t.getSelectionHintsJson() == null) return 0;
        try {
            Map<?, ?> hints = objectMapper.readValue(t.getSelectionHintsJson(), Map.class);
            int weight = hints.get("selectionWeight") instanceof Number n ? n.intValue() : 0;

            @SuppressWarnings("unchecked")
            List<String> archetypes = (List<String>) hints.get("archetypes");
            if (archetypes != null && !archetypes.contains("all") &&
                !archetypes.contains(direction.name())) {
                weight -= 5;
            }

            @SuppressWarnings("unchecked")
            List<String> suitableFor = (List<String>) hints.get("suitableFor");
            if (suitableFor != null && !suitableFor.contains("all") && brand != null) {
                String industry = brand.industry() != null ? brand.industry().toLowerCase() : "";
                boolean match = suitableFor.stream().anyMatch(s -> industry.contains(s.toLowerCase()));
                if (match) weight += 3;
            }
            return weight;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Derive which section IDs to include for this engagement.
     * Currently returns the template's defaultIncluded set. Future: orchestrator
     * can override per-brand (e.g. omit stationery for digital-only brands).
     */
    public Set<String> resolveIncludedSections(BrandBookTemplate template) {
        if (template.getSectionManifestJson() == null) return Set.of();
        try {
            List<Map<String, Object>> manifest = objectMapper.readValue(
                template.getSectionManifestJson(), new TypeReference<>() {});
            return manifest.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("required"))
                    || Boolean.TRUE.equals(s.get("defaultIncluded")))
                .map(s -> (String) s.get("id"))
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Could not parse section manifest for template {}: {}", template.getId(), e.getMessage());
            return Set.of();
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public BrandBookTemplate create(String name, String description, String classpathPath,
                                    String sectionManifestJson, String selectionHintsJson) {
        BrandBookTemplate t = new BrandBookTemplate();
        t.setId(UUID.randomUUID().toString());
        t.setName(name);
        t.setDescription(description);
        t.setStatus(BrandBookTemplate.Status.CURRENT);
        t.setClasspathPath(classpathPath);
        t.setSectionManifestJson(sectionManifestJson);
        t.setSelectionHintsJson(selectionHintsJson);
        return templateRepo.save(t);
    }

    public BrandBookTemplate setStatus(String id, BrandBookTemplate.Status status) {
        BrandBookTemplate t = get(id);
        t.setStatus(status);
        t.setUpdatedAt(Instant.now());
        htmlCache.remove(id); // invalidate cache on any change
        return templateRepo.save(t);
    }

    public void delete(String id) {
        templateRepo.deleteById(id);
        htmlCache.remove(id);
    }

    public BrandBookTemplate update(String id, String name, String description,
                                    String sectionManifestJson, String selectionHintsJson) {
        BrandBookTemplate t = get(id);
        if (name != null) t.setName(name);
        if (description != null) t.setDescription(description);
        if (sectionManifestJson != null) t.setSectionManifestJson(sectionManifestJson);
        if (selectionHintsJson != null) t.setSelectionHintsJson(selectionHintsJson);
        t.setUpdatedAt(Instant.now());
        htmlCache.remove(id);
        return templateRepo.save(t);
    }

    // ── Section manifest for the Classic template ─────────────────────────────

    private static final String CLASSIC_MANIFEST = """
        [
          {"id":"cover",           "name":"Cover",                   "required":true,  "defaultIncluded":true},
          {"id":"toc",             "name":"Table of Contents",        "required":true,  "defaultIncluded":true},
          {"id":"welcome",         "name":"Welcome Letter",           "required":true,  "defaultIncluded":true},
          {"id":"vision",          "name":"Vision",                   "required":false, "defaultIncluded":true},
          {"id":"values",          "name":"Values",                   "required":false, "defaultIncluded":true},
          {"id":"logo-system",     "name":"Logo System",              "required":true,  "defaultIncluded":true},
          {"id":"logo-variants",   "name":"Logo Colour Versions",     "required":false, "defaultIncluded":true},
          {"id":"logo-usage",      "name":"Logo Clear Space",         "required":false, "defaultIncluded":false},
          {"id":"logo-donts",      "name":"Logo Do's & Don'ts",       "required":false, "defaultIncluded":false},
          {"id":"colours",         "name":"Brand Colours",            "required":true,  "defaultIncluded":true},
          {"id":"gradients",       "name":"Brand Gradients",          "required":false, "defaultIncluded":true},
          {"id":"graphic-devices", "name":"Graphic Devices",          "required":false, "defaultIncluded":true},
          {"id":"photography",     "name":"Photography Direction",    "required":false, "defaultIncluded":true},
          {"id":"typography",      "name":"Typography",               "required":true,  "defaultIncluded":true},
          {"id":"type-in-use",     "name":"Type in Use",              "required":false, "defaultIncluded":true},
          {"id":"grid-portrait",   "name":"Portrait Grid",            "required":false, "defaultIncluded":false},
          {"id":"grid-landscape",  "name":"Landscape Grid",           "required":false, "defaultIncluded":false},
          {"id":"brand-application","name":"Brand Application",       "required":false, "defaultIncluded":true},
          {"id":"stationery",      "name":"Stationery",               "required":false, "defaultIncluded":true},
          {"id":"brand-language",  "name":"Brand Language",           "required":false, "defaultIncluded":true},
          {"id":"tone-of-voice",   "name":"Tone of Voice",            "required":false, "defaultIncluded":true},
          {"id":"closing",         "name":"Closing",                  "required":true,  "defaultIncluded":true},
          {"id":"back-cover",      "name":"Back Cover",               "required":true,  "defaultIncluded":true}
        ]
        """.strip();

    // ── Section manifest for the Meridian template (landscape) ───────────────

    private static final String MERIDIAN_MANIFEST = """
        [
          {"id":"cover",           "name":"Cover",                   "required":true,  "defaultIncluded":true},
          {"id":"toc",             "name":"Table of Contents",        "required":true,  "defaultIncluded":true},
          {"id":"welcome",         "name":"Welcome Letter",           "required":true,  "defaultIncluded":true},
          {"id":"vision",          "name":"Vision",                   "required":false, "defaultIncluded":true},
          {"id":"values",          "name":"Values",                   "required":false, "defaultIncluded":true},
          {"id":"logo-system",     "name":"Logo System",              "required":true,  "defaultIncluded":true},
          {"id":"logo-variants",   "name":"Logo Colour Versions",     "required":false, "defaultIncluded":true},
          {"id":"logo-usage",      "name":"Logo Clear Space",         "required":false, "defaultIncluded":false},
          {"id":"logo-donts",      "name":"Logo Do's & Don'ts",       "required":false, "defaultIncluded":false},
          {"id":"colours",         "name":"Brand Colours",            "required":true,  "defaultIncluded":true},
          {"id":"gradients",       "name":"Brand Gradients",          "required":false, "defaultIncluded":true},
          {"id":"graphic-devices", "name":"Graphic Devices",          "required":false, "defaultIncluded":true},
          {"id":"photography",     "name":"Photography Direction",    "required":false, "defaultIncluded":true},
          {"id":"typography",      "name":"Typography",               "required":true,  "defaultIncluded":true},
          {"id":"type-in-use",     "name":"Type in Use",              "required":false, "defaultIncluded":true},
          {"id":"grid-portrait",   "name":"Portrait Grid",            "required":false, "defaultIncluded":false},
          {"id":"grid-landscape",  "name":"Landscape Grid",           "required":false, "defaultIncluded":true},
          {"id":"brand-application","name":"Brand Application",       "required":false, "defaultIncluded":true},
          {"id":"stationery",      "name":"Stationery",               "required":false, "defaultIncluded":true},
          {"id":"brand-language",  "name":"Brand Language",           "required":false, "defaultIncluded":true},
          {"id":"tone-of-voice",   "name":"Tone of Voice",            "required":false, "defaultIncluded":true},
          {"id":"closing",         "name":"Closing",                  "required":true,  "defaultIncluded":true},
          {"id":"back-cover",      "name":"Back Cover",               "required":true,  "defaultIncluded":true}
        ]
        """.strip();

    // ── Section manifest for the Ember template (portrait, expressive) ────────

    private static final String EMBER_MANIFEST = """
        [
          {"id":"cover",           "name":"Cover",                   "required":true,  "defaultIncluded":true},
          {"id":"toc",             "name":"Table of Contents",        "required":true,  "defaultIncluded":true},
          {"id":"welcome",         "name":"Welcome Letter",           "required":true,  "defaultIncluded":true},
          {"id":"vision",          "name":"Vision",                   "required":false, "defaultIncluded":true},
          {"id":"values",          "name":"Values",                   "required":false, "defaultIncluded":true},
          {"id":"logo-system",     "name":"Logo System",              "required":true,  "defaultIncluded":true},
          {"id":"logo-variants",   "name":"Logo Colour Versions",     "required":false, "defaultIncluded":true},
          {"id":"logo-usage",      "name":"Logo Clear Space",         "required":false, "defaultIncluded":false},
          {"id":"logo-donts",      "name":"Logo Do's & Don'ts",       "required":false, "defaultIncluded":false},
          {"id":"colours",         "name":"Brand Colours",            "required":true,  "defaultIncluded":true},
          {"id":"gradients",       "name":"Brand Gradients",          "required":false, "defaultIncluded":true},
          {"id":"graphic-devices", "name":"Graphic Devices",          "required":false, "defaultIncluded":true},
          {"id":"photography",     "name":"Photography Direction",    "required":false, "defaultIncluded":true},
          {"id":"typography",      "name":"Typography",               "required":true,  "defaultIncluded":true},
          {"id":"type-in-use",     "name":"Type in Use",              "required":false, "defaultIncluded":true},
          {"id":"grid-portrait",   "name":"Portrait Grid",            "required":false, "defaultIncluded":true},
          {"id":"grid-landscape",  "name":"Landscape Grid",           "required":false, "defaultIncluded":false},
          {"id":"brand-application","name":"Brand Application",       "required":false, "defaultIncluded":true},
          {"id":"stationery",      "name":"Stationery",               "required":false, "defaultIncluded":true},
          {"id":"brand-language",  "name":"Brand Language",           "required":false, "defaultIncluded":true},
          {"id":"tone-of-voice",   "name":"Tone of Voice",            "required":false, "defaultIncluded":true},
          {"id":"closing",         "name":"Closing",                  "required":true,  "defaultIncluded":true},
          {"id":"back-cover",      "name":"Back Cover",               "required":true,  "defaultIncluded":true}
        ]
        """.strip();
}
