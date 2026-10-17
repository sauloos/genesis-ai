package ai.genesisbrands.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Retrieves relevant knowledge chunks from Qdrant via REST API.
 * Uses port 6333 (REST) so the same endpoint works for both the Java app
 * and the Python ingestion pipeline — a single port for Container Apps.
 */
@Service
public class RetrievalService {

    private final EmbeddingModel embeddingModel;
    private final RestTemplate restTemplate;

    @Value("${qdrant.rest.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.rest.api-key:}")
    private String qdrantApiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}")
    private String collectionName;

    private static final double SIMILARITY_THRESHOLD = 0.45;

    public RetrievalService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.restTemplate = new RestTemplate();
    }

    @SuppressWarnings("unchecked")
    public String retrieve(String query, int topK) {
        float[] embedding = embeddingModel.embed(query);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            headers.set("api-key", qdrantApiKey);
        }

        Map<String, Object> body = Map.of(
            "vector", toList(embedding),
            "limit", topK,
            "score_threshold", SIMILARITY_THRESHOLD,
            "with_payload", true
        );

        String url = qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points/search";

        try {
            Map<String, Object> response = restTemplate.postForObject(
                url, new HttpEntity<>(body, headers), Map.class);

            if (response == null) return "";
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("result");
            if (results == null || results.isEmpty()) return "";

            return results.stream()
                .map(point -> {
                    Map<String, Object> payload = (Map<String, Object>) point.get("payload");
                    if (payload == null) return "";
                    String text       = str(payload, "text");
                    String title      = str(payload, "title");
                    String sourceUrl  = str(payload, "source_url");
                    String sourceType = str(payload, "source_type");
                    String source     = !title.isBlank() ? title : sourceUrl;
                    return String.format("[Source: %s (%s)]\n%s", source, sourceType, text);
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));

        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Retrieves precedent and training knowledge for a specialist agent.
     * Combines layer2 client precedent with training content that applies to this
     * agent — both GLOBAL training (applies to all agents) and content explicitly
     * scoped to this agent's asset type.
     */
    @SuppressWarnings("unchecked")
    public List<String> retrieveForAgent(String query, String agentType, int topK) {
        try {
            List<Float> vector = toList(embeddingModel.embed(query));

            // Fetch layer2 precedent + all training content in one query,
            // then post-filter training results by scope/asset_types in Java.
            Map<String, Object> body = Map.of(
                "vector", vector,
                "limit", topK * 3, // over-fetch so post-filter leaves enough
                "with_payload", true,
                "filter", Map.of("should", List.of(
                    Map.of("key", "layer", "match", Map.of("value", "layer2")),
                    Map.of("key", "layer", "match", Map.of("value", "training"))
                ))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (qdrantApiKey != null && !qdrantApiKey.isBlank())
                headers.set("api-key", qdrantApiKey);

            String url = qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points/search";
            Map<String, Object> response = restTemplate.postForObject(
                url, new HttpEntity<>(body, headers), Map.class);

            if (response == null) return List.of();
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("result");
            if (results == null || results.isEmpty()) return List.of();

            return results.stream()
                .map(r -> (Map<String, Object>) r.get("payload"))
                .filter(p -> p != null && p.containsKey("text"))
                .filter(p -> {
                    String layer = str(p, "layer");
                    if ("layer2".equals(layer)) return true;
                    if ("training".equals(layer)) {
                        String scope = str(p, "scope");
                        if ("GLOBAL".equals(scope)) return true;
                        if ("ASSET_SCOPED".equals(scope)) {
                            String assetTypes = str(p, "asset_types");
                            return assetTypes.contains(agentType);
                        }
                    }
                    return false;
                })
                .limit(topK)
                .map(p -> str(p, "text"))
                .filter(t -> !t.isBlank())
                .collect(Collectors.toList());

        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Structured similarity search restricted to the given {@code layer} payload values
     * (e.g. "layer1", "layer2") — the Brain connector's retrieval primitive. Unlike
     * {@link #retrieve}, failures propagate: an external caller should see an error, not an
     * empty result that looks like "the brain knows nothing".
     */
    @SuppressWarnings("unchecked")
    public List<KnowledgeHit> search(String query, int topK, List<String> layers) {
        if (layers == null || layers.isEmpty()) return List.of();
        Map<String, Object> body = Map.of(
            "vector", toList(embeddingModel.embed(query)),
            "limit", topK,
            "score_threshold", SIMILARITY_THRESHOLD,
            "with_payload", true,
            "filter", Map.of("should", layers.stream()
                .map(l -> Map.of("key", "layer", "match", Map.of("value", l)))
                .toList())
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) headers.set("api-key", qdrantApiKey);

        String url = qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points/search";
        Map<String, Object> response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
        if (response == null || response.get("result") == null) return List.of();

        return ((List<Map<String, Object>>) response.get("result")).stream()
            .filter(point -> point.get("payload") instanceof Map)
            .map(point -> {
                Map<String, Object> p = (Map<String, Object>) point.get("payload");
                double score = point.get("score") instanceof Number n ? n.doubleValue() : 0;
                return new KnowledgeHit(str(p, "text"), str(p, "title"), str(p, "source_url"),
                    str(p, "source_type"), str(p, "layer"), score);
            })
            .filter(h -> !h.text().isBlank())
            .toList();
    }

    public record KnowledgeHit(String text, String title, String sourceUrl, String sourceType,
                               String layer, double score) {}

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private static List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}
