package ai.genesisbrands.service;

import ai.genesisbrands.model.IngestionEvent;
import ai.genesisbrands.model.IngestionEvent.Kind;
import ai.genesisbrands.model.IngestionEvent.Status;
import ai.genesisbrands.model.IngestionPipeline;
import ai.genesisbrands.repository.IngestionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionExecutionService {

    private final IngestionEventRepository eventRepository;

    @Value("${genesis.ingestion.home-dir:/app/ingestion}")
    private String ingestionHomeDir;

    @Value("${qdrant.rest.url:http://localhost:6333}")
    private String qdrantRestUrl;

    @Value("${qdrant.rest.api-key:}")
    private String qdrantApiKey;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Value("${spring.ai.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}")
    private String collectionName;

    private ExecutorService executor;

    // Patterns to parse ingest.py stdout into counts
    private static final Pattern ALREADY_INGESTED = Pattern.compile("Already ingested, skipping:");
    private static final Pattern INGESTED_CHUNKS  = Pattern.compile("Ingested (\\d+) chunks");
    private static final Pattern SKIPPED_ERROR    = Pattern.compile("Skipped \\(error\\):");
    private static final Pattern PROGRESS_LINE    = Pattern.compile("^\\[(\\d+)/(\\d+)\\]");
    private static final Pattern DONE_SUMMARY     = Pattern.compile("Done\\.\\s*(\\d+)/(\\d+)");

    @PostConstruct
    private void init() {
        executor = Executors.newFixedThreadPool(2);
    }

    public IngestionEvent runSingleItem(Kind kind, String sourceRef, String layer,
                                        String contentCategory, String contextNote,
                                        boolean force, MultipartFile uploadedFile) {
        IngestionEvent event = createEvent(null, kind, sourceRef, layer);
        executor.submit(() -> executeItem(event, kind, sourceRef, layer, contentCategory,
                contextNote, force, uploadedFile));
        return event;
    }

    public IngestionEvent runPipeline(IngestionPipeline pipeline) {
        Kind kind = pipeline.getType() == IngestionPipeline.Type.BLOG_CRAWL
                ? Kind.BLOG_CRAWL : Kind.YOUTUBE_CHANNEL;
        IngestionEvent event = createEvent(pipeline.getId(), kind, pipeline.getTargetUrl(),
                pipeline.getLayer());
        executor.submit(() -> executePipeline(event, pipeline));
        return event;
    }

    private IngestionEvent createEvent(String pipelineId, Kind kind, String sourceLabel,
                                       String layer) {
        IngestionEvent event = new IngestionEvent();
        event.setId(UUID.randomUUID().toString());
        event.setPipelineId(pipelineId);
        event.setKind(kind);
        event.setSourceLabel(sourceLabel);
        event.setLayer(layer);
        event.setStatus(Status.RUNNING);
        event.setStartedAt(Instant.now());
        return eventRepository.save(event);
    }

    private void executeItem(IngestionEvent event, Kind kind, String sourceRef, String layer,
                             String contentCategory, String contextNote, boolean force,
                             MultipartFile uploadedFile) {
        Path tempFile = null;
        try {
            List<String> args = new ArrayList<>();
            args.add("python3");
            args.add("ingest.py");

            switch (kind) {
                case SINGLE_URL -> args.addAll(List.of("--url", sourceRef));
                case SINGLE_YOUTUBE -> args.addAll(List.of("--youtube", sourceRef));
                case SINGLE_VIMEO -> args.addAll(List.of("--vimeo", sourceRef));
                case SINGLE_FILE -> {
                    if (uploadedFile == null) throw new IllegalArgumentException("File required");
                    String filename = Objects.requireNonNull(uploadedFile.getOriginalFilename());
                    String ext = filename.contains(".")
                            ? filename.substring(filename.lastIndexOf('.')).toLowerCase() : ".txt";
                    tempFile = Files.createTempFile("ingest_", ext);
                    uploadedFile.transferTo(tempFile.toFile());
                    String flag = switch (ext) {
                        case ".pdf" -> "--pdf";
                        case ".pptx" -> "--pptx";
                        default -> "--txt";
                    };
                    args.addAll(List.of(flag, tempFile.toString()));
                }
                default -> throw new IllegalArgumentException("Unsupported single-item kind: " + kind);
            }

            if (layer != null && !layer.isBlank()) args.addAll(List.of("--layer", layer));
            if (contentCategory != null && !contentCategory.isBlank())
                args.addAll(List.of("--type", contentCategory));
            if (contextNote != null && !contextNote.isBlank())
                args.addAll(List.of("--context", contextNote));
            if (force) args.add("--force");

            runProcess(event, args);
        } catch (Exception e) {
            log.error("Single-item ingestion failed: {}", e.getMessage(), e);
            finalizeEvent(event, Status.FAILED, e.getMessage() + "\n");
        } finally {
            if (tempFile != null) { try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {} }
        }
    }

    private void executePipeline(IngestionEvent event, IngestionPipeline pipeline) {
        try {
            List<String> args = new ArrayList<>();
            args.add("python3");
            args.add("ingest.py");

            if (pipeline.getType() == IngestionPipeline.Type.BLOG_CRAWL) {
                args.addAll(List.of("--crawl", pipeline.getTargetUrl()));
            } else {
                args.addAll(List.of("--channel", pipeline.getTargetUrl()));
            }

            String layer = pipeline.getLayer();
            if (layer != null && !layer.isBlank()) args.addAll(List.of("--layer", layer));
            if (pipeline.getContentCategory() != null && !pipeline.getContentCategory().isBlank())
                args.addAll(List.of("--type", pipeline.getContentCategory()));

            runProcess(event, args);
        } catch (Exception e) {
            log.error("Pipeline ingestion failed: {}", e.getMessage(), e);
            finalizeEvent(event, Status.FAILED, e.getMessage() + "\n");
        }
    }

    private void runProcess(IngestionEvent event, List<String> args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(new File(ingestionHomeDir));
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        // Rename QDRANT_REST_URL → QDRANT_URL as ingest.py expects
        env.put("QDRANT_URL", qdrantRestUrl);
        env.put("QDRANT_API_KEY", qdrantApiKey);
        env.put("COLLECTION_NAME", collectionName);
        if (!openAiApiKey.isBlank())   env.put("OPENAI_API_KEY",   openAiApiKey);
        if (!anthropicApiKey.isBlank()) env.put("ANTHROPIC_API_KEY", anthropicApiKey);

        log.info("Starting ingestion: {}", args);
        Process process = pb.start();

        StringBuilder logBuffer = new StringBuilder();
        int linesSinceFlush = 0;
        int itemsFound = 0, itemsIngested = 0, itemsSkipped = 0, itemsFailed = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logBuffer.append(line).append("\n");
                linesSinceFlush++;

                // Parse counts from known log patterns
                if (ALREADY_INGESTED.matcher(line).find()) itemsSkipped++;
                if (INGESTED_CHUNKS.matcher(line).find())  itemsIngested++;
                if (SKIPPED_ERROR.matcher(line).find())    itemsFailed++;

                Matcher progressMatcher = PROGRESS_LINE.matcher(line);
                if (progressMatcher.find()) {
                    itemsFound = Integer.parseInt(progressMatcher.group(2));
                }

                Matcher doneMatcher = DONE_SUMMARY.matcher(line);
                if (doneMatcher.find()) {
                    itemsIngested = Integer.parseInt(doneMatcher.group(1));
                    itemsFound    = Integer.parseInt(doneMatcher.group(2));
                    itemsSkipped  = itemsFound - itemsIngested - itemsFailed;
                }

                // Flush log to DB every 25 lines for live progress
                if (linesSinceFlush >= 25) {
                    flushProgress(event, logBuffer.toString(), itemsFound,
                            itemsIngested, itemsSkipped, itemsFailed);
                    linesSinceFlush = 0;
                }
            }
        }

        int exitCode = process.waitFor();
        Status status = (exitCode != 0) ? Status.FAILED
                : (itemsFailed > 0)     ? Status.PARTIAL
                : Status.SUCCESS;

        finalizeEvent(event, status, logBuffer.toString(),
                itemsFound, itemsIngested, itemsSkipped, itemsFailed);
    }

    private void flushProgress(IngestionEvent event, String log, int found,
                               int ingested, int skipped, int failed) {
        event.setLogOutput(log);
        event.setItemsFound(found);
        event.setItemsIngested(ingested);
        event.setItemsSkipped(skipped);
        event.setItemsFailed(failed);
        eventRepository.save(event);
    }

    private void finalizeEvent(IngestionEvent event, Status status, String extraLog) {
        finalizeEvent(event, status, extraLog,
                event.getItemsFound(), event.getItemsIngested(),
                event.getItemsSkipped(), event.getItemsFailed());
    }

    private void finalizeEvent(IngestionEvent event, Status status, String log,
                               int found, int ingested, int skipped, int failed) {
        event.setStatus(status);
        event.setLogOutput(log);
        event.setItemsFound(found);
        event.setItemsIngested(ingested);
        event.setItemsSkipped(skipped);
        event.setItemsFailed(failed);
        event.setFinishedAt(Instant.now());
        eventRepository.save(event);
        log().info("Ingestion {} finished: {}", event.getId(), status);
    }

    private org.slf4j.Logger log() { return log; }
}
