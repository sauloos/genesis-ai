package ai.genesisbrands.service;

import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookOutput;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place that reads {@code Engagement.purposePlaybookJson} and turns it into context.
 *
 * <p>A client's Purpose Playbook is meant to be present in every later interaction they have with
 * Genesis — the consultant chat, a standing agent's unattended run, a regeneration. Those callers
 * want the same document rendered the same way, and none of them should be parsing stored JSON or
 * inventing its own idea of what "this client's brand context" means, so they all come here.
 *
 * <p>Two deliberate properties:
 *
 * <ul>
 *   <li><b>It is resolved server-side from the database, per call, from an identity only.</b> No
 *       caller passes in brand context and no caller may. Accepting client-supplied context would
 *       let one client's chat produce output grounded in another client's brand, so the only input
 *       here is an engagement id, which a caller gets from the authenticated client's own
 *       {@code ClientOwnSubjectResolver} lookup and never from a request.</li>
 *   <li><b>It renders the whole written document, not a selection.</b> A "core sections" shortlist
 *       would be a second, silent opinion about what matters, drifting from
 *       {@code assembly/purpose-playbook-structure.yaml}. The document is around 34k characters —
 *       smaller than the raw 131-question transcript this replaces as consultant context — so
 *       there is nothing to buy by trimming it.</li>
 * </ul>
 *
 * <p>The confidence map is rendered too, and that is the point of including it: it tells whatever
 * is reading where this document is thin, so an agent can hedge instead of asserting a brand fact
 * that rests on a skipped question.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurposePlaybookContextService {

    private final EngagementRepository engagementRepo;
    private final ObjectMapper objectMapper;

    /** The playbook for one engagement, or empty when that engagement ran before the instrument. */
    public Optional<PurposePlaybookOutput> forEngagement(String engagementId) {
        return engagementRepo.findById(engagementId).flatMap(this::parse);
    }

    private Optional<PurposePlaybookOutput> parse(Engagement engagement) {
        String json = engagement.getPurposePlaybookJson();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, PurposePlaybookOutput.class));
        } catch (Exception e) {
            // A stored playbook that will not parse is worth a loud log and nothing else: the
            // caller's job (a chat turn, a standing run) is better done with less context than
            // failed outright, and every caller treats empty as "this engagement has none".
            log.error("Could not parse the stored Purpose Playbook for engagement {}: {}",
                engagement.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The playbook as markdown for a system prompt.
     *
     * @param heading the top-level heading, so a caller can say whose playbook this is in its own
     *                voice ("This client's Purpose Playbook" reads differently in a consultant
     *                chat and in an unattended standing run)
     */
    public String render(PurposePlaybookOutput playbook, String heading) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(heading).append("\n\n");
        sb.append("This is the agreed strategic foundation for this client, derived from their own "
            + "discovery answers. It is the brief for everything made for them. Where it says a "
            + "field is thin or not on record, treat that as a gap to work around or ask about — "
            + "never as licence to invent the missing fact.\n\n");

        for (PurposePlaybookOutput.Section section : playbook.sections().values()) {
            if (section == null || section.body() == null || section.body().isBlank()) {
                continue;
            }
            sb.append("### ").append(section.key());
            if (section.title() != null && !section.title().isBlank()) {
                sb.append(" — ").append(section.title());
            }
            if (section.confidence() != null && !section.confidence().isBlank()) {
                sb.append(" (confidence: ").append(section.confidence()).append(")");
            }
            sb.append("\n").append(section.body().strip()).append("\n\n");
        }

        lookTerritory(playbook).ifPresent(text ->
            sb.append("### The look-and-feel territory\n").append(text).append("\n"));

        if (!playbook.confidence().isEmpty()) {
            sb.append("### Where this playbook is unsure\n");
            for (PurposePlaybookOutput.Gap gap : playbook.confidence()) {
                sb.append("- ").append(gap.field()).append(": ").append(gap.confidence());
                if (gap.whatWouldRaiseIt() != null && !gap.whatWouldRaiseIt().isBlank()) {
                    sb.append(" — would be raised by: ").append(gap.whatWouldRaiseIt());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * The typed look-and-feel territory as prose, or empty if the playbook has none.
     *
     * <p>Its own method because it is read in two places that must not drift: the rendered document
     * a consultant or standing run sees, and the Layer 2 record, where a client's resolved taste is
     * some of the most reusable precedent in the whole playbook.
     */
    public Optional<String> lookTerritory(PurposePlaybookOutput playbook) {
        PurposePlaybookOutput.LookTerritory look = playbook.lookTerritory();
        if (look == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        append(sb, "In a word", String.join(", ", look.summaryWords()));
        append(sb, "Posture", look.posture());
        append(sb, "Palette", look.palette());
        append(sb, "Typography", look.typography());
        append(sb, "Logo", look.logo());
        append(sb, "Imagery", look.imagery());
        if (!look.tensions().isEmpty()) {
            // Layer 1 is explicit that these are never averaged away, so they are carried as
            // unresolved tensions rather than reconciled into a single preference.
            sb.append("Unresolved tensions in the client's own taste answers — state these "
                + "rather than splitting the difference:\n");
            look.tensions().forEach(t -> sb.append("- ").append(t).append("\n"));
        }
        append(sb, "Confidence", look.confidence());
        return sb.isEmpty() ? Optional.empty() : Optional.of(sb.toString());
    }

    private void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value.strip()).append("\n");
        }
    }

    /**
     * The playbook grouped into its pillars, which is the unit Layer 2 is ingested in.
     *
     * <p>Per pillar rather than per section or per document, for retrieval reasons on both sides:
     * a single 34k-character point would match everything weakly and return the whole document for
     * any query, while a 500-character subsection point ("belong.lineOfCircle") retrieves a
     * fragment with no indication of the brand it belongs to. A pillar — all of {@code belong},
     * all of {@code belief} — is a coherent, self-describing passage of a few thousand characters.
     *
     * <p>The pillar is the section key's first segment, which is how
     * {@code purpose-playbook-structure.yaml} names them, so a new section added to Layer 1 lands
     * in the right pillar without this method being touched.
     */
    public List<Pillar> pillars(PurposePlaybookOutput playbook) {
        Map<String, List<PurposePlaybookOutput.Section>> byPillar = new LinkedHashMap<>();
        for (PurposePlaybookOutput.Section section : playbook.sections().values()) {
            if (section == null || section.key() == null
                || section.body() == null || section.body().isBlank()) {
                continue;
            }
            String pillar = section.key().contains(".")
                ? section.key().substring(0, section.key().indexOf('.'))
                : section.key();
            byPillar.computeIfAbsent(pillar, k -> new ArrayList<>()).add(section);
        }

        List<Pillar> pillars = new ArrayList<>();
        for (Map.Entry<String, List<PurposePlaybookOutput.Section>> entry : byPillar.entrySet()) {
            StringBuilder text = new StringBuilder();
            for (PurposePlaybookOutput.Section section : entry.getValue()) {
                text.append(section.key());
                if (section.title() != null && !section.title().isBlank()) {
                    text.append(" — ").append(section.title());
                }
                text.append("\n").append(section.body().strip()).append("\n\n");
            }
            pillars.add(new Pillar(entry.getKey(), text.toString().strip(), entry.getValue().size()));
        }
        return pillars;
    }

    /** One pillar of a playbook, ready to embed. */
    public record Pillar(String pillar, String text, int sectionCount) {}
}
