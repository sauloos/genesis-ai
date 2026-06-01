package com.genesisai.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Retrieves relevant knowledge chunks from Qdrant given a query.
 */
@Service
public class RetrievalService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    public RetrievalService(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    public String retrieve(String query, int topK) {
        var results = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.65)
                .build()
        );

        if (results == null || results.isEmpty()) {
            return "";
        }

        return results.stream()
            .map(doc -> {
                var meta = doc.getMetadata();
                String source = meta.getOrDefault("title", meta.getOrDefault("source_url", "Unknown")).toString();
                String type = meta.getOrDefault("source_type", "").toString();
                return String.format("[Source: %s (%s)]\n%s", source, type, doc.getText());
            })
            .collect(Collectors.joining("\n\n---\n\n"));
    }
}
