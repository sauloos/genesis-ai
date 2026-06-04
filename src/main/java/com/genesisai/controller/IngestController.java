package com.genesisai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/ingest")
@Tag(name = "Admin — Ingest", description = "On-demand knowledge base ingestion endpoints")
public class IngestController {

    private final EmbeddingModel embeddingModel;
    private final RestTemplate restTemplate;

    @Value("${qdrant.rest.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.rest.api-key:}")
    private String qdrantApiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}")
    private String collectionName;

    public IngestController(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.restTemplate = new RestTemplate();
    }

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Ingest plain text directly")
    public IngestResponse ingestText(@RequestBody IngestTextRequest req) {
        Map<String, Object> payload = Map.of(
            "text",        req.content(),
            "source_url",  req.sourceUrl()  != null ? req.sourceUrl()  : "manual",
            "source_type", "text",
            "title",       req.title()      != null ? req.title()      : "",
            "author",      req.author()     != null ? req.author()     : "",
            "layer",       "layer1"
        );
        upsert(req.content(), payload);
        return new IngestResponse("accepted", 1);
    }

    @PostMapping("/file")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Upload a plain text or markdown file")
    public IngestResponse ingestFile(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title
    ) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String name = title != null ? title : file.getOriginalFilename();
        Map<String, Object> payload = Map.of(
            "text",        content,
            "source_url",  "upload://" + file.getOriginalFilename(),
            "source_type", "upload",
            "title",       name != null ? name : "",
            "layer",       "layer1"
        );
        upsert(content, payload);
        return new IngestResponse("accepted", 1);
    }

    private void upsert(String text, Map<String, Object> payload) {
        float[] embedding = embeddingModel.embed(text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            headers.set("api-key", qdrantApiKey);
        }

        Map<String, Object> point = Map.of(
            "id",      UUID.randomUUID().toString(),
            "vector",  toList(embedding),
            "payload", payload
        );
        Map<String, Object> body = Map.of("points", List.of(point));

        String url = qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points";
        restTemplate.put(url, new HttpEntity<>(body, headers));
    }

    private static List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    public record IngestTextRequest(String content, String title, String author, String sourceUrl) {}
    public record IngestResponse(String status, int documentsQueued) {}
}
