package ai.genesisbrands.controller;

import ai.genesisbrands.service.Layer1Service;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final Path CHUNKS_DIR = Paths.get("knowledge/layer1/chunks");

    private final Layer1Service layer1Service;

    @Value("${qdrant.rest.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.rest.api-key:}")
    private String qdrantApiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}")
    private String collectionName;

    private final RestTemplate restTemplate = new RestTemplate();

    // ── Modules ───────────────────────────────────────────────────────────────

    @GetMapping("/modules")
    public List<ModuleItem> listModules() {
        return layer1Service.listModules().entrySet().stream()
            .map(e -> {
                String key = e.getKey();
                String[] parts = key.split("[/\\\\]");
                String category = parts.length > 1 ? parts[0] : "general";
                String name = parts[parts.length - 1].replaceAll("\\.(yaml|yml)$", "");
                return new ModuleItem(key, category, name, e.getValue());
            })
            .collect(Collectors.toList());
    }

    // ── Sources ───────────────────────────────────────────────────────────────

    @GetMapping("/sources")
    public List<SourceSummary> listSources() {
        // Scroll all layer1 points from Qdrant and group by source_id
        List<Map<String, Object>> allPoints = scrollQdrant("layer1");

        Map<String, SourceSummary> bySourceId = new LinkedHashMap<>();
        for (Map<String, Object> point : allPoints) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) point.get("payload");
            if (payload == null) continue;

            String sourceId  = (String) payload.get("source_id");
            if (sourceId == null) continue;

            bySourceId.computeIfAbsent(sourceId, id -> {
                String url       = (String) payload.getOrDefault("source_url", "");
                String title     = (String) payload.getOrDefault("title", "");
                String ingestedAt = (String) payload.getOrDefault("ingested_at", "");
                // Prefer explicit content_category (set at ingestion) over source_type (ingestion method)
                String srcType = payload.containsKey("content_category")
                    ? (String) payload.get("content_category")
                    : (String) payload.getOrDefault("source_type", "");
                Object totalChunks = payload.get("total_chunks");
                int chunks = totalChunks instanceof Number n ? n.intValue() : 1;
                String domain = extractDomain(url);
                return new SourceSummary(id, url, domain, title, ingestedAt, chunks, srcType);
            });
        }
        return new ArrayList<>(bySourceId.values());
    }

    @GetMapping("/sources/{sourceId}")
    public SourceDetail getSource(@PathVariable String sourceId) {
        // Fetch all chunks for this source from Qdrant
        List<Map<String, Object>> points = scrollQdrantBySourceId(sourceId);
        if (points.isEmpty()) {
            throw new NoSuchElementException("Source not found: " + sourceId);
        }

        // Build summary from first chunk
        @SuppressWarnings("unchecked")
        Map<String, Object> firstPayload = (Map<String, Object>) points.get(0).get("payload");
        String url       = (String) firstPayload.getOrDefault("source_url", "");
        String title     = (String) firstPayload.getOrDefault("title", "");
        String author    = (String) firstPayload.getOrDefault("author", "");
        String date      = (String) firstPayload.getOrDefault("date", "");
        String ingestedAt = (String) firstPayload.getOrDefault("ingested_at", "");
        String domain    = extractDomain(url);

        // Collect chunk texts sorted by chunk_index
        List<String> chunks = points.stream()
            .sorted(Comparator.comparingInt(p -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> pl = (Map<String, Object>) p.get("payload");
                Object idx = pl.get("chunk_index");
                return idx instanceof Number n ? n.intValue() : 0;
            }))
            .map(p -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> pl = (Map<String, Object>) p.get("payload");
                return (String) pl.getOrDefault("text", "");
            })
            .filter(t -> !t.isBlank())
            .collect(Collectors.toList());

        return new SourceDetail(sourceId, url, domain, title, author, date, ingestedAt,
            chunks.size(), chunks.isEmpty() ? "" : chunks.get(0));
    }

    @PostMapping("/admin/reclassify-web-as-blog")
    public Map<String, Object> reclassifyWebAsBlog() {
        // Bulk-set content_category=blog for all layer1 points where source_type=web
        // and content_category is not already set.
        HttpHeaders headers = qdrantHeaders();
        Map<String, Object> body = Map.of(
            "payload", Map.of("content_category", "blog"),
            "filter", Map.of("must", List.of(
                Map.of("key", "layer",       "match", Map.of("value", "layer1")),
                Map.of("key", "source_type", "match", Map.of("value", "web"))
            ))
        );
        try {
            restTemplate.postForObject(
                qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points/payload",
                new HttpEntity<>(body, headers), Map.class);
            log.info("reclassifyWebAsBlog: completed");
            return Map.of("status", "ok", "message", "All layer1 web points tagged with content_category=blog");
        } catch (Exception e) {
            log.warn("reclassifyWebAsBlog failed: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @DeleteMapping("/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSource(@PathVariable String sourceId) {
        // Delete from Qdrant by source_id filter
        deleteQdrantBySourceId(sourceId);

        // Also remove local chunk files if present
        try {
            Files.deleteIfExists(CHUNKS_DIR.resolve(sourceId + "_meta.json"));
            Files.deleteIfExists(CHUNKS_DIR.resolve(sourceId + "_chunks.json"));
        } catch (Exception e) {
            log.debug("Could not delete local chunk files for {}: {}", sourceId, e.getMessage());
        }
        log.info("KnowledgeController: deleted source {}", sourceId);
    }

    // ── Qdrant helpers ────────────────────────────────────────────────────────

    private List<Map<String, Object>> scrollQdrant(String layer) {
        List<Map<String, Object>> all = new ArrayList<>();
        Object offset = null;
        HttpHeaders headers = qdrantHeaders();

        do {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("filter", Map.of("must", List.of(
                Map.of("key", "layer", "match", Map.of("value", layer)))));
            body.put("with_payload", true);
            body.put("with_vectors", false);
            body.put("limit", 250);
            if (offset != null) body.put("offset", offset);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = restTemplate.postForObject(
                    qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points/scroll",
                    new HttpEntity<>(body, headers), Map.class);

                if (resp == null) break;
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) resp.get("result");
                if (result == null) break;

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> points = (List<Map<String, Object>>) result.get("points");
                if (points != null) all.addAll(points);

                offset = result.get("next_page_offset");
            } catch (Exception e) {
                log.warn("Qdrant scroll failed: {}", e.getMessage());
                break;
            }
        } while (offset != null);

        return all;
    }

    private List<Map<String, Object>> scrollQdrantBySourceId(String sourceId) {
        List<Map<String, Object>> all = new ArrayList<>();
        HttpHeaders headers = qdrantHeaders();

        Map<String, Object> body = Map.of(
            "filter", Map.of("must", List.of(
                Map.of("key", "source_id", "match", Map.of("value", sourceId)))),
            "with_payload", true,
            "with_vectors", false,
            "limit", 100);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(
                qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points/scroll",
                new HttpEntity<>(body, headers), Map.class);

            if (resp != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) resp.get("result");
                if (result != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> points = (List<Map<String, Object>>) result.get("points");
                    if (points != null) all.addAll(points);
                }
            }
        } catch (Exception e) {
            log.warn("Qdrant scroll by source_id failed for {}: {}", sourceId, e.getMessage());
        }
        return all;
    }

    private void deleteQdrantBySourceId(String sourceId) {
        HttpHeaders headers = qdrantHeaders();
        Map<String, Object> body = Map.of(
            "filter", Map.of("must", List.of(
                Map.of("key", "source_id", "match", Map.of("value", sourceId)))));
        try {
            restTemplate.postForObject(
                qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points/delete",
                new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.warn("Qdrant delete by source_id failed for {}: {}", sourceId, e.getMessage());
        }
    }

    private HttpHeaders qdrantHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) h.set("api-key", qdrantApiKey);
        return h;
    }

    private static String extractDomain(String url) {
        if (url == null || url.isBlank()) return "unknown";
        try {
            String stripped = url.replaceAll("^https?://", "").replaceAll("^www\\.", "");
            int slash = stripped.indexOf('/');
            return slash > 0 ? stripped.substring(0, slash) : stripped;
        } catch (Exception e) {
            return url;
        }
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record ModuleItem(String key, String category, String name, String content) {}

    public record SourceSummary(
        String sourceId, String sourceUrl, String domain,
        String title, String ingestedAt, int chunkCount, String sourceType) {}

    public record SourceDetail(
        String sourceId, String sourceUrl, String domain,
        String title, String author, String date,
        String ingestedAt, int chunkCount, String firstChunk) {}
}
