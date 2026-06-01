package com.genesisai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoint for on-demand document ingestion.
 * UI devs can call this to drop new content into the knowledge base without the CLI.
 */
@RestController
@RequestMapping("/api/admin/ingest")
@RequiredArgsConstructor
@Tag(name = "Admin — Ingest", description = "On-demand knowledge base ingestion endpoints")
public class IngestController {

    private final VectorStore vectorStore;

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Ingest plain text directly")
    public IngestResponse ingestText(@RequestBody IngestTextRequest req) {
        var doc = new Document(
            req.content(),
            Map.of(
                "source_url", req.sourceUrl() != null ? req.sourceUrl() : "manual",
                "source_type", "text",
                "title", req.title() != null ? req.title() : "",
                "author", req.author() != null ? req.author() : "",
                "layer", "layer1"
            )
        );
        vectorStore.add(List.of(doc));
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
        var doc = new Document(
            content,
            Map.of(
                "source_url", "upload://" + file.getOriginalFilename(),
                "source_type", "upload",
                "title", name != null ? name : "",
                "layer", "layer1"
            )
        );
        vectorStore.add(List.of(doc));
        return new IngestResponse("accepted", 1);
    }

    public record IngestTextRequest(String content, String title, String author, String sourceUrl) {}
    public record IngestResponse(String status, int documentsQueued) {}
}
