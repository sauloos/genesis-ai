package ai.genesisbrands.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the parts of the ingest that do not need Qdrant: the point ids that make a re-ingest
 * replace rather than duplicate, and the payload contract the readers depend on.
 */
class Layer2IngestServiceTest {

    private final Layer2IngestService service = new Layer2IngestService(null);

    @Test
    void thePointIdIsDerivedFromTheSourceSoAReIngestOverwrites() {
        // The production store accumulated hundreds of duplicate sources because the scripted
        // ingest used random ids. The same record must land on the same points every time.
        assertThat(Layer2IngestService.idFor("engagement/e1/purpose-playbook", 0))
            .isEqualTo(Layer2IngestService.idFor("engagement/e1/purpose-playbook", 0));
        assertThat(Layer2IngestService.idFor("engagement/e1/purpose-playbook", 0))
            .isNotEqualTo(Layer2IngestService.idFor("engagement/e1/purpose-playbook", 1));
        assertThat(Layer2IngestService.idFor("engagement/e1/purpose-playbook", 0))
            .isNotEqualTo(Layer2IngestService.idFor("engagement/e2/purpose-playbook", 0));
        // Qdrant accepts a UUID or an unsigned integer, nothing else.
        assertThat(Layer2IngestService.idFor("s", 0)).matches("[0-9a-f-]{36}");
    }

    @Test
    void thePayloadCarriesTheKeysTheReadersExpect() {
        Map<String, Object> payload = service.payloadFor(record(),
            new Layer2IngestService.Chunk("the belief pillar"), 1, 4);

        // RetrievalService filters on layer and reads text/title/source_url/source_type; the
        // knowledge admin page groups on source_id and reads content_category and the chunk counts.
        assertThat(payload).containsEntry("layer", "layer2");
        assertThat(payload).containsEntry("text", "the belief pillar");
        assertThat(payload).containsEntry("source_id", "engagement/e1/purpose-playbook");
        assertThat(payload).containsEntry("source_url", "/api/engagements/e1");
        assertThat(payload).containsEntry("title", "Hartwell Structural — Purpose Playbook");
        assertThat(payload).containsEntry("content_category", "purpose-playbook");
        assertThat(payload).containsEntry("chunk_index", 1);
        assertThat(payload).containsEntry("total_chunks", 4);
        assertThat(payload).containsKey("ingested_at");
    }

    @Test
    void tenantMetadataIsCarriedButCannotRewriteTheKeysTheServiceOwns() {
        Map<String, Object> payload = service.payloadFor(record(),
            new Layer2IngestService.Chunk("text", Map.of(
                "pillar", "belief",
                "client_user_id", "client-7",
                // A tenant must not be able to put its content into another layer, by accident or
                // otherwise — the layer a point claims is the only thing separating the corpora.
                "layer", "layer1",
                "text", "something else")),
            0, 1);

        assertThat(payload).containsEntry("pillar", "belief");
        assertThat(payload).containsEntry("client_user_id", "client-7");
        assertThat(payload).containsEntry("layer", "layer2");
        assertThat(payload).containsEntry("text", "text");
    }

    @Test
    void aRecordWithNothingWorthEmbeddingWritesNothing() {
        // No Qdrant and no embedding model here, so reaching either would throw: returning 0
        // before touching them is the behaviour being asserted.
        Layer2IngestService.Record empty = new Layer2IngestService.Record(
            "engagement/e1/purpose-playbook", "/api/engagements/e1", "t", "purpose-playbook",
            java.util.Arrays.asList(new Layer2IngestService.Chunk("   "), null));

        assertThat(service.ingest(empty)).isZero();
    }

    private static Layer2IngestService.Record record() {
        return new Layer2IngestService.Record(
            "engagement/e1/purpose-playbook", "/api/engagements/e1",
            "Hartwell Structural — Purpose Playbook", "purpose-playbook", List.of());
    }
}
