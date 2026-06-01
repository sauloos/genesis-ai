package com.genesisai.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Retrieves relevant knowledge chunks from Qdrant given a query.
 * Uses the Qdrant client directly to read our custom payload field ("text").
 */
@Service
public class RetrievalService {

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}")
    private String collectionName;

    private static final float SIMILARITY_THRESHOLD = 0.45f;

    public RetrievalService(QdrantClient qdrantClient, EmbeddingModel embeddingModel) {
        this.qdrantClient = qdrantClient;
        this.embeddingModel = embeddingModel;
    }

    public String retrieve(String query, int topK) {
        float[] embedding = embeddingModel.embed(query);

        SearchPoints request = SearchPoints.newBuilder()
            .setCollectionName(collectionName)
            .addAllVector(toFloatList(embedding))
            .setLimit(topK)
            .setScoreThreshold(SIMILARITY_THRESHOLD)
            .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
            .build();

        List<ScoredPoint> results;
        try {
            results = qdrantClient.searchAsync(request).get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return "";
        }

        if (results == null || results.isEmpty()) {
            return "";
        }

        return results.stream()
            .map(point -> {
                var payload = point.getPayloadMap();
                String text = payload.containsKey("text") ? payload.get("text").getStringValue() : "";
                String title = payload.containsKey("title") ? payload.get("title").getStringValue() : "";
                String sourceUrl = payload.containsKey("source_url") ? payload.get("source_url").getStringValue() : "";
                String sourceType = payload.containsKey("source_type") ? payload.get("source_type").getStringValue() : "";
                String source = !title.isBlank() ? title : sourceUrl;
                return String.format("[Source: %s (%s)]\n%s", source, sourceType, text);
            })
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining("\n\n---\n\n"));
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}
