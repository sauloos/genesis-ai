package ai.genesisbrands.gardener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads ingested sources back out of the knowledge store (Qdrant), where ingest.py wrote them as
 * chunks sharing a source_id. The gardener never writes to the store.
 */
@Slf4j
@Component
class GardenerSources {

    record Source(String sourceId, String title, String url, String layer, Instant ingestedAt) {}

    private static final List<String> SUMMARY_FIELDS = List.of("source_id", "title", "source_url", "layer", "ingested_at");

    private final RestTemplate http = new RestTemplate();

    @Value("${qdrant.rest.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.rest.api-key:}")
    private String qdrantApiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}")
    private String collection;

    /** Sources in a layer whose chunks were written at or after {@code since}, newest first. */
    List<Source> ingestedSince(String layer, Instant since) {
        Map<String, Source> bySource = new LinkedHashMap<>();
        Object offset = null;
        int pages = 0;
        do {
            Map<String, Object> body = new HashMap<>();
            body.put("filter", Map.of("must", List.of(Map.of("key", "layer", "match", Map.of("value", layer)))));
            body.put("with_payload", SUMMARY_FIELDS);
            body.put("with_vectors", false);
            body.put("limit", 500);
            if (offset != null) body.put("offset", offset);
            Map<String, Object> result = scroll(body);
            if (result == null) break;
            for (Map<String, Object> payload : payloads(result)) {
                Source s = source(payload);
                if (s == null || s.ingestedAt() == null || s.ingestedAt().isBefore(since)) continue;
                bySource.merge(s.sourceId(), s, (a, b) -> a.ingestedAt().isAfter(b.ingestedAt()) ? a : b);
            }
            offset = result.get("next_page_offset");
        } while (offset != null && ++pages < 200);
        List<Source> sources = new ArrayList<>(bySource.values());
        sources.sort(Comparator.comparing(Source::ingestedAt).reversed());
        return sources;
    }

    Optional<Source> find(String sourceId) {
        return chunks(sourceId).stream().map(GardenerSources::source).filter(java.util.Objects::nonNull).findFirst();
    }

    /** The source's text in chunk order, cut at {@code maxChars}. */
    String text(String sourceId, int maxChars) {
        List<Map<String, Object>> chunks = chunks(sourceId);
        chunks.sort(Comparator.comparingInt(p -> p.get("chunk_index") instanceof Number n ? n.intValue() : Integer.MAX_VALUE));
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> chunk : chunks) {
            if (!(chunk.get("text") instanceof String t) || t.isBlank()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(t.strip());
            if (out.length() >= maxChars) return out.substring(0, maxChars) + "\n[cut]";
        }
        return out.toString();
    }

    private List<Map<String, Object>> chunks(String sourceId) {
        Map<String, Object> body = Map.of(
            "filter", Map.of("must", List.of(Map.of("key", "source_id", "match", Map.of("value", sourceId)))),
            "with_payload", true, "with_vectors", false, "limit", 500);
        Map<String, Object> result = scroll(body);
        return result == null ? new ArrayList<>() : new ArrayList<>(payloads(result));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scroll(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) headers.set("api-key", qdrantApiKey);
        try {
            Map<String, Object> resp = http.postForObject(
                qdrantUrl.replaceAll("/$", "") + "/collections/" + collection + "/points/scroll",
                new HttpEntity<>(body, headers), Map.class);
            return resp == null ? null : (Map<String, Object>) resp.get("result");
        } catch (Exception e) {
            log.warn("Gardener could not read the knowledge store: {}", e.getMessage());
            throw new IllegalStateException("The knowledge store could not be read: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> payloads(Map<String, Object> result) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (result.get("points") instanceof List<?> points) {
            for (Object p : points) {
                if (p instanceof Map<?, ?> point && point.get("payload") instanceof Map<?, ?> payload) out.add((Map<String, Object>) payload);
            }
        }
        return out;
    }

    private static Source source(Map<String, Object> payload) {
        if (!(payload.get("source_id") instanceof String id) || id.isBlank()) return null;
        return new Source(id, str(payload.get("title")), str(payload.get("source_url")), str(payload.get("layer")),
            instant(payload.get("ingested_at")));
    }

    private static String str(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    static Instant instant(Object o) {
        if (!(o instanceof String s) || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(s);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
