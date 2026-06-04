package com.genesisai.service;

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
