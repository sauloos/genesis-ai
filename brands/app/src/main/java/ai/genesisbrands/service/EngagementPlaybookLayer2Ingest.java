package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookOutput;
import ai.genesisbrands.model.Engagement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a finished engagement's Purpose Playbook into Layer 2, so the next client's engagement can
 * learn from it.
 *
 * <p>This is the tenant half of the ingest: {@link Layer2IngestService} owns embedding, payload
 * keys and idempotency, and this class owns the only question core cannot answer — what, out of a
 * completed engagement, is worth remembering. The answer is the playbook, not the deliverables. A
 * tagline or a palette is the answer to one client's problem and is of no use to the next; the
 * reasoning that produced it — how a position was argued from a contradiction in the client's own
 * answers, how a taste profile resolved into a look — is what transfers.
 *
 * <p>The unit is the pillar, for retrieval reasons on both sides. A single point holding the whole
 * 34k-character document would match every query weakly and return the entire playbook as
 * "precedent"; a point per subsection would return a paragraph with no indication of the brand it
 * belonged to. A pillar — all of {@code belief}, all of {@code belong} — is a few thousand
 * characters of coherent, self-describing argument, which is the right size for the thing a later
 * engagement actually wants to see.
 *
 * <p>Ingest is keyed on the engagement id, so an engagement that is re-run replaces its own record
 * rather than adding a second copy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EngagementPlaybookLayer2Ingest {

    /** What a Layer 2 listing calls these records, and how a later query can select only them. */
    private static final String CATEGORY = "purpose-playbook";

    private final PurposePlaybookContextService playbookContext;
    private final Layer2IngestService layer2;
    private final ObjectMapper objectMapper;

    /**
     * Ingests this engagement's playbook, if it has one.
     *
     * <p>Called after the engagement is saved as complete, and deliberately does not throw: the
     * client's work is already finished and stored, and a vector store that is briefly unreachable
     * is a reason to lose precedent, not a reason to fail an engagement.
     *
     * @return how many chunks were written
     */
    public int ingest(Engagement engagement) {
        return playbookContext.forEngagement(engagement.getId())
            .map(playbook -> layer2.ingest(record(engagement, playbook)))
            .orElse(0);
    }

    private Layer2IngestService.Record record(Engagement engagement, PurposePlaybookOutput playbook) {
        String brandName = brandName(engagement);
        List<Layer2IngestService.Chunk> chunks = new ArrayList<>();

        for (PurposePlaybookContextService.Pillar pillar : playbookContext.pillars(playbook)) {
            // Each chunk names the brand and the pillar in its own text. A retrieved passage
            // arrives on its own, with no surrounding document, so a point that does not say whose
            // playbook it is and which part of it it answers is precedent an agent cannot place.
            String text = "Purpose Playbook — " + brandName + " (" + pillar.pillar() + ")\n\n"
                + pillar.text();
            chunks.add(new Layer2IngestService.Chunk(text, metadata(engagement, pillar.pillar())));
        }

        playbookContext.lookTerritory(playbook).ifPresent(look -> chunks.add(
            new Layer2IngestService.Chunk(
                "Purpose Playbook — " + brandName + " (resolved look-and-feel territory)\n\n" + look,
                metadata(engagement, "look"))));

        return new Layer2IngestService.Record(
            sourceId(engagement.getId()),
            "/api/engagements/" + engagement.getId(),
            brandName + " — Purpose Playbook",
            CATEGORY,
            chunks);
    }

    private Map<String, String> metadata(Engagement engagement, String pillar) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("engagement_id", engagement.getId());
        metadata.put("pillar", pillar);
        // Carried so that the anonymisation pass Layer 2 still needs can find a client's own
        // records, and so a client's material can be removed on request. It is never a retrieval
        // filter: a client's own context is resolved from the database, not searched for here.
        if (engagement.getClientUserId() != null) {
            metadata.put("client_user_id", engagement.getClientUserId());
        }
        return metadata;
    }

    /** Stable per engagement, so re-running one replaces its record instead of duplicating it. */
    private static String sourceId(String engagementId) {
        return "engagement/" + engagementId + "/purpose-playbook";
    }

    /**
     * The business name as the engagement's own briefs settled it, which is the only place it is
     * recorded — {@code Engagement.clientName} is the contact person, not the business.
     *
     * <p>Falls back to the engagement id rather than to anything invented. A playbook whose name
     * never got established is still worth remembering for its reasoning, and labelling it with a
     * guess would put a fabricated brand name into every future engagement's precedent.
     */
    private String brandName(Engagement engagement) {
        if (engagement.getBriefsJson() == null || engagement.getBriefsJson().isBlank()) {
            return "engagement " + engagement.getId();
        }
        try {
            List<DirectionBrief> briefs = objectMapper.readValue(
                engagement.getBriefsJson(), new TypeReference<List<DirectionBrief>>() {});
            String name = briefs.isEmpty() || briefs.get(0).brand() == null
                ? null : briefs.get(0).brand().name();
            return name == null || name.isBlank() ? "engagement " + engagement.getId() : name;
        } catch (Exception e) {
            log.warn("Could not read the brand name from engagement {}'s briefs: {}",
                engagement.getId(), e.getMessage());
            return "engagement " + engagement.getId();
        }
    }
}
