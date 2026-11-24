package ai.genesisbrands.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a completed piece of client work into Layer 2 — the project memory every later engagement
 * draws on as precedent.
 *
 * <p>This is the mechanism only. It knows about embeddings, Qdrant payload keys and idempotency; it
 * knows nothing about engagements, playbooks or brands. What is worth remembering from a finished
 * piece of work is the tenant's judgement, supplied as a {@link Record} of {@link Chunk}s — the same
 * division as {@code ConsultantSubjectProvider} and {@code ClientWorkspaceProvider}.
 *
 * <p>Three properties are deliberate:
 *
 * <ul>
 *   <li><b>Ingesting the same source twice replaces it.</b> Every write deletes the existing points
 *       for that {@code sourceId} before upserting, and point ids are derived from the source id
 *       rather than random. An engagement that is re-run, or a record whose chunk count shrinks,
 *       leaves one copy behind rather than two overlapping ones. This is not a theoretical concern:
 *       the production store accumulated hundreds of duplicate sources precisely because the
 *       scripted ingest wrote random ids.</li>
 *   <li><b>The payload matches what already reads it.</b> {@code text}, {@code layer},
 *       {@code source_id}, {@code source_url}, {@code title}, {@code content_category},
 *       {@code ingested_at}, {@code chunk_index} and {@code total_chunks} are the keys
 *       {@code RetrievalService} and the knowledge admin page expect, so an ingested record is
 *       searchable and listable the moment it is written, with no reader changes.</li>
 *   <li><b>Failure is reported, never thrown.</b> Callers ingest at the end of work that has
 *       already succeeded — losing a client's finished engagement because the vector store was
 *       briefly unreachable would be a far worse outcome than a gap in precedent.</li>
 * </ul>
 *
 * <p>One caveat worth stating where it will be read: Layer 2 is pooled precedent, not per-client
 * storage. {@code RetrievalService.retrieveForAgent} matches any {@code layer2} point for any
 * client, which is the point of project memory — but it means anything written here can inform
 * another client's output, and Layer 2 is not yet anonymised on ingest. Each chunk therefore
 * carries the identifiers an anonymisation pass will need to find it again. A client's own context
 * must never be fetched from here: it is resolved from the database per request, by
 * {@code PurposePlaybookContextService} and the subject providers.
 */
@Slf4j
@Service
public class Layer2IngestService {

    public static final String LAYER = "layer2";

    private final EmbeddingModel embeddingModel;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${qdrant.rest.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.rest.api-key:}")
    private String qdrantApiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}")
    private String collectionName;

    public Layer2IngestService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * One passage of a record, with whatever extra payload the tenant wants searchable alongside
     * it (a pillar name, a direction, an agent id). Keys that this service owns — {@code text},
     * {@code layer}, {@code source_id} and the rest listed in the class javadoc — are set by the
     * service and win over anything of the same name here.
     */
    public record Chunk(String text, Map<String, String> metadata) {
        public Chunk(String text) {
            this(text, Map.of());
        }
    }

    /**
     * A thing worth remembering, as the tenant sees it.
     *
     * @param sourceId        stable and the tenant's own — re-ingesting the same id replaces what
     *                        is there, so it should identify the work, not the run (an engagement
     *                        id, not a timestamp)
     * @param sourceUrl       where a human can go to see it, for the knowledge admin page
     * @param title           how it should read in a list of sources
     * @param contentCategory what kind of record this is, e.g. {@code purpose-playbook}
     * @param chunks          the passages to embed, in reading order
     */
    public record Record(String sourceId, String sourceUrl, String title, String contentCategory,
                         List<Chunk> chunks) {}

    /**
     * Replaces this source in Layer 2 with the given chunks.
     *
     * @return how many chunks were written, or 0 if there was nothing to write or the write failed
     */
    public int ingest(Record record) {
        List<Chunk> chunks = record.chunks() == null ? List.<Chunk>of() : record.chunks().stream()
            .filter(c -> c != null && c.text() != null && !c.text().isBlank())
            .toList();
        if (chunks.isEmpty()) {
            log.debug("Layer 2 ingest for {} had nothing to write", record.sourceId());
            return 0;
        }

        try {
            deleteSource(record.sourceId());

            List<Map<String, Object>> points = new ArrayList<>(chunks.size());
            String ingestedAt = Instant.now().toString();
            for (int i = 0; i < chunks.size(); i++) {
                points.add(Map.of(
                    "id", pointId(record.sourceId(), i),
                    "vector", vector(chunks.get(i).text()),
                    "payload", payload(record, chunks.get(i), i, chunks.size(), ingestedAt)));
            }
            restTemplate.put(pointsUrl(), new HttpEntity<>(Map.of("points", points), headers()));

            log.info("Ingested {} chunk(s) into Layer 2 for source {} ({})",
                points.size(), record.sourceId(), record.contentCategory());
            return points.size();
        } catch (Exception e) {
            // The work this records has already completed and been saved. Precedent for future
            // engagements is worth having, but not worth failing a finished engagement for.
            log.error("Layer 2 ingest failed for source {}: {}", record.sourceId(), e.getMessage());
            return 0;
        }
    }

    /** Removes every Layer 2 point for this source, so a re-ingest cannot leave two copies. */
    private void deleteSource(String sourceId) {
        Map<String, Object> body = Map.of("filter", Map.of("must", List.of(
            Map.of("key", "layer", "match", Map.of("value", LAYER)),
            Map.of("key", "source_id", "match", Map.of("value", sourceId)))));
        restTemplate.postForObject(pointsUrl() + "/delete", new HttpEntity<>(body, headers()), Map.class);
    }

    private Map<String, Object> payload(Record record, Chunk chunk, int index, int total, String ingestedAt) {
        Map<String, Object> payload = new HashMap<>();
        if (chunk.metadata() != null) payload.putAll(chunk.metadata());
        // Set last: the keys this service owns are a contract with the readers, and a tenant's
        // metadata must not be able to overwrite the layer a point claims to be in.
        payload.put("text", chunk.text().strip());
        payload.put("layer", LAYER);
        payload.put("source_id", record.sourceId());
        payload.put("source_url", record.sourceUrl() == null ? "" : record.sourceUrl());
        payload.put("title", record.title() == null ? "" : record.title());
        payload.put("source_type", "engagement");
        payload.put("content_category", record.contentCategory() == null ? "" : record.contentCategory());
        payload.put("ingested_at", ingestedAt);
        payload.put("chunk_index", index);
        payload.put("total_chunks", total);
        return payload;
    }

    /**
     * A point id derived from the source and the chunk's place in it, so the same record always
     * writes to the same points. Qdrant requires a UUID or an unsigned integer, which is why this
     * is a name-based UUID rather than the readable {@code sourceId#index}.
     */
    private static String pointId(String sourceId, int index) {
        return UUID.nameUUIDFromBytes((sourceId + "#" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private List<Float> vector(String text) {
        float[] embedding = embeddingModel.embed(text);
        List<Float> vector = new ArrayList<>(embedding.length);
        for (float f : embedding) vector.add(f);
        return vector;
    }

    private String pointsUrl() {
        return qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points";
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) headers.set("api-key", qdrantApiKey);
        return headers;
    }

    /** Visible for tests: the payload a chunk would be stored with. */
    Map<String, Object> payloadFor(Record record, Chunk chunk, int index, int total) {
        return new LinkedHashMap<>(payload(record, chunk, index, total, "2026-01-01T00:00:00Z"));
    }

    /** Visible for tests: the deterministic id a chunk would be stored under. */
    static String idFor(String sourceId, int index) {
        return pointId(sourceId, index);
    }
}
