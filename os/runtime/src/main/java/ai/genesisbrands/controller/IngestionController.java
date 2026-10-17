package ai.genesisbrands.controller;

import ai.genesisbrands.model.IngestionEvent;
import ai.genesisbrands.model.IngestionEvent.Kind;
import ai.genesisbrands.model.IngestionEvent.Status;
import ai.genesisbrands.model.IngestionPipeline;
import ai.genesisbrands.repository.IngestionEventRepository;
import ai.genesisbrands.repository.IngestionPipelineRepository;
import ai.genesisbrands.service.IngestionExecutionService;
import ai.genesisbrands.service.IngestionPipelineScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionPipelineRepository pipelineRepository;
    private final IngestionEventRepository eventRepository;
    private final IngestionExecutionService executionService;

    // ── Pipelines ──────────────────────────────────────────────────────────────

    @GetMapping("/pipelines")
    public List<IngestionPipeline> listPipelines() {
        return pipelineRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/pipelines")
    public IngestionPipeline createPipeline(@RequestBody IngestionPipeline body) {
        body.setId(UUID.randomUUID().toString());
        body.setCreatedAt(Instant.now());
        if (body.getSchedule() != IngestionPipeline.Schedule.MANUAL) {
            body.setNextRunAt(IngestionPipelineScheduler.computeNextRun(body));
        }
        return pipelineRepository.save(body);
    }

    @PutMapping("/pipelines/{id}")
    public ResponseEntity<IngestionPipeline> updatePipeline(
            @PathVariable String id, @RequestBody IngestionPipeline body) {
        return pipelineRepository.findById(id).map(existing -> {
            existing.setName(body.getName());
            existing.setTargetUrl(body.getTargetUrl());
            existing.setLayer(body.getLayer());
            existing.setContentCategory(body.getContentCategory());
            existing.setDateFrom(body.getDateFrom());
            existing.setDateTo(body.getDateTo());
            existing.setSchedule(body.getSchedule());
            existing.setActive(body.isActive());
            if (body.getSchedule() != IngestionPipeline.Schedule.MANUAL && body.isActive()) {
                existing.setNextRunAt(IngestionPipelineScheduler.computeNextRun(existing));
            } else if (body.getSchedule() == IngestionPipeline.Schedule.MANUAL) {
                existing.setNextRunAt(null);
            }
            return ResponseEntity.ok(pipelineRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/pipelines/{id}")
    public ResponseEntity<Void> deletePipeline(@PathVariable String id) {
        if (!pipelineRepository.existsById(id)) return ResponseEntity.notFound().build();
        pipelineRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pipelines/{id}/run")
    public ResponseEntity<IngestionEvent> runPipelineNow(@PathVariable String id) {
        return pipelineRepository.findById(id).map(p -> {
            IngestionEvent event = executionService.runPipeline(p);
            p.setLastRunAt(Instant.now());
            p.setNextRunAt(IngestionPipelineScheduler.computeNextRun(p));
            pipelineRepository.save(p);
            return ResponseEntity.ok(event);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/pipelines/{id}/pause")
    public ResponseEntity<IngestionPipeline> pausePipeline(@PathVariable String id) {
        return pipelineRepository.findById(id).map(p -> {
            p.setActive(false);
            p.setNextRunAt(null);
            return ResponseEntity.ok(pipelineRepository.save(p));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/pipelines/{id}/resume")
    public ResponseEntity<IngestionPipeline> resumePipeline(@PathVariable String id) {
        return pipelineRepository.findById(id).map(p -> {
            p.setActive(true);
            if (p.getSchedule() != IngestionPipeline.Schedule.MANUAL) {
                p.setNextRunAt(IngestionPipelineScheduler.computeNextRun(p));
            }
            return ResponseEntity.ok(pipelineRepository.save(p));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Events ─────────────────────────────────────────────────────────────────

    @GetMapping("/events")
    public List<IngestionEvent> listEvents(
            @RequestParam(required = false) String pipelineId,
            @RequestParam(required = false) String status) {
        if (pipelineId != null) {
            return eventRepository.findAllByPipelineIdOrderByStartedAtDesc(pipelineId);
        }
        if (status != null) {
            return eventRepository.findAllByStatus(Status.valueOf(status.toUpperCase()));
        }
        return eventRepository.findTop20ByOrderByStartedAtDesc();
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<IngestionEvent> getEvent(@PathVariable String id) {
        return eventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/events/ingest-item")
    public IngestionEvent ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "layer1") String layer,
            @RequestParam(required = false) String contentCategory,
            @RequestParam(required = false) String contextNote,
            @RequestParam(defaultValue = "false") boolean force) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.')).toLowerCase() : ".txt";
        Kind kind = switch (ext) {
            case ".pdf" -> Kind.SINGLE_FILE;
            case ".pptx" -> Kind.SINGLE_FILE;
            default -> Kind.SINGLE_FILE;
        };
        return executionService.runSingleItem(kind, filename, layer, contentCategory,
                contextNote, force, file);
    }

    @PostMapping("/events/ingest-url")
    public IngestionEvent ingestUrl(@RequestBody Map<String, String> body) {
        String kind   = body.getOrDefault("kind", "url");
        String source = body.get("source");
        String layer  = body.getOrDefault("layer", "layer1");
        String cat    = body.get("contentCategory");
        String ctx    = body.get("contextNote");
        boolean force = Boolean.parseBoolean(body.getOrDefault("force", "false"));

        Kind eventKind = switch (kind.toLowerCase()) {
            case "youtube" -> Kind.SINGLE_YOUTUBE;
            case "vimeo"   -> Kind.SINGLE_VIMEO;
            default        -> Kind.SINGLE_URL;
        };
        return executionService.runSingleItem(eventKind, source, layer, cat, ctx, force, null);
    }
}
