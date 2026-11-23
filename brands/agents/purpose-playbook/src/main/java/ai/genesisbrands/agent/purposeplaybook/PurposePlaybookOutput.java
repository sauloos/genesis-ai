package ai.genesisbrands.agent.purposeplaybook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Purpose Playbook as data.
 *
 * <p>Shape comes straight from {@code assembly/purpose-playbook-structure.yaml}'s
 * {@code output_contract}: section and subsection keys, each holding rendered prose plus the
 * signal keys it was derived from and a confidence value. The rendered document, the per-pillar
 * Layer 2 chunks and every downstream brief are all produced from this one object, so the keys
 * are the contract — nothing downstream should ever parse the prose to find a field.
 *
 * <p>{@code sections} is keyed by {@code <section>} or {@code <section>.<subsection>}
 * ({@code situation}, {@code belief.mission}, {@code strategy.channels}). Two things are lifted
 * out of that map and typed, because they are read programmatically rather than rendered:
 * {@code lookTerritory}, which {@code directions/look-and-feel-kit.yaml} declares as the object
 * every specialist agent reads, and {@code confidence}, which the facilitator reads before the
 * client meeting and which the evaluator checks is non-empty.
 */
public record PurposePlaybookOutput(
    String engagementId,
    Map<String, Section> sections,
    LookTerritory lookTerritory,
    List<Gap> confidence,
    String reasoning,
    int iteration
) {

    public PurposePlaybookOutput {
        sections = sections == null ? Map.of() : new LinkedHashMap<>(sections);
        confidence = confidence == null ? List.of() : List.copyOf(confidence);
    }

    /**
     * One rendered section of the document.
     *
     * @param sources     the signal keys this prose was derived from. The rubric's groundedness
     *                    dimension checks these name signals that actually carry content, so an
     *                    empty array on a written section is a defect, not a formality.
     * @param confidence  high / medium / thin
     */
    public record Section(String key, String title, String body, List<String> sources, String confidence) {
        public Section {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /**
     * The look-and-feel territory, exactly as {@code look-and-feel-kit.yaml}'s output contract
     * declares it. Carried in {@code strategy.lookAndFeel} as prose as well, but typed here
     * because the logo and visual-identity agents read the fields rather than the paragraph.
     *
     * @param tensions  every place the forced-choice pairs contradicted the sliders. Layer 1 is
     *                  explicit that these are stated and never silently averaged away — the gap
     *                  between how a client sees the business and what they actually reach for is
     *                  often the most useful line in the document.
     */
    public record LookTerritory(
        List<String> summaryWords,
        String posture,
        String palette,
        String typography,
        String logo,
        String imagery,
        List<String> tensions,
        String confidence
    ) {
        public LookTerritory {
            summaryWords = summaryWords == null ? List.of() : List.copyOf(summaryWords);
            tensions = tensions == null ? List.of() : List.copyOf(tensions);
        }
    }

    /**
     * One row of the confidence map: a field, how sure we are, and the single question that
     * would raise it. This is what turns a gap into a fifteen-minute follow-up instead of a
     * brand built on a guess.
     */
    public record Gap(String field, String confidence, String whatWouldRaiseIt) {}

    public Section section(String key) {
        return sections.get(key);
    }

    /** Every section body, in insertion order — what the deterministic guard scans. */
    public List<String> bodies() {
        return sections.values().stream().map(Section::body).filter(b -> b != null).toList();
    }
}
