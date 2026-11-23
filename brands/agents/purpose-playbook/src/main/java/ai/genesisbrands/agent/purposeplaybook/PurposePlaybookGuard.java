package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.service.Layer1Service;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mechanically decidable half of the rubric, checked in code before a model ever sees the
 * draft.
 *
 * <p>{@code evaluation/purpose-playbook-rubric.yaml} lists ten rejection triggers. Most need
 * judgement — whether a competitor critique matches the page, whether a value's behavioural
 * definition is supported by what the client said it cost them. A handful do not: a missing
 * section, an empty {@code sources} array, the word "avatar", a never-list word that has been
 * dropped, a colour value the client never named. Those are facts about the document, and
 * deciding them with a regex instead of an Opus call is both cheaper and more reliable — a model
 * asked to check thirty things will sometimes miss the mechanical one.
 *
 * <p>This is a pre-filter, never a replacement. A clean guard says nothing about whether the
 * document is any good; it only means the model evaluator's attention is not spent on
 * bookkeeping.
 */
@Component
public class PurposePlaybookGuard {

    /** `  - key: belief` (a section) or `      - key: mission` (a subsection). */
    private static final Pattern SECTION_KEY = Pattern.compile("^(\\s*)- key:\\s*([A-Za-z0-9_]+)\\s*$");
    private static final Pattern REQUIRED = Pattern.compile("^\\s*required:\\s*(true|false)\\s*$");

    /**
     * Marks the section above it as a pillar container rather than a section with prose of its own.
     * {@code belief} is {@code required: true} and holds mission, vision, values and the never-list;
     * what is required is those four, not a body keyed {@code belief}.
     */
    private static final Pattern SUBSECTIONS = Pattern.compile("^\\s*subsections:\\s*$");

    /** A colour value in any CSS-ish notation. */
    private static final Pattern COLOUR_VALUE = Pattern.compile(
        "#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3}(?:[0-9a-fA-F]{2})?)?\\b|\\b(?:rgb|rgba|hsl|hsla)\\s*\\(");

    /** Brand-playbook fields this document must not pre-empt, as a labelled section. */
    private static final List<String> OUT_OF_SCOPE_TITLES = List.of(
        "tagline", "elevator pitch", "boilerplate", "positioning statement", "brand promise",
        "values statement");

    /**
     * The CAUSE scaffold as a labelled section. Matched case-sensitively on the acronym, because
     * the ordinary word is perfectly fine in a title and "because" contains it.
     */
    private static final Pattern CAUSE_ACRONYM = Pattern.compile("\\bCAUSE\\b");

    /** The confidence map is lifted out of `sections` into its own typed list. */
    private static final String CONFIDENCE_SECTION = "confidence";

    private final Layer1Service layer1Service;

    public PurposePlaybookGuard(Layer1Service layer1Service) {
        this.layer1Service = layer1Service;
    }

    /** Every violation found, as the sentences the evaluator hands back as specific issues. */
    public List<String> check(PurposePlaybookInput input, PurposePlaybookOutput output) {
        List<String> violations = new ArrayList<>();
        Map<String, PurposePlaybookOutput.Section> sections = output.sections();

        missingSections(sections).forEach(key -> violations.add(
            "Required section \"" + key + "\" is missing. The structure module marks it required: true."));

        sections.forEach((key, section) -> {
            if (section.body() == null || section.body().isBlank()) {
                violations.add("Section \"" + key + "\" has an empty body.");
            }
            if (section.sources().isEmpty()) {
                violations.add("Section \"" + key + "\" names no sources. Groundedness requires the "
                    + "signal keys it was derived from, and an unsourced section cannot be audited.");
            }
        });

        if (output.confidence().isEmpty()) {
            violations.add("The confidence section is empty. It is required on every engagement, and "
                + "an empty one is a rejection trigger: list each field resting on a thin or missing "
                + "signal with the one question that would close it.");
        }

        avatar(output).forEach(violations::add);
        neverList(input, sections).forEach(violations::add);
        outOfScopeTitles(sections).forEach(violations::add);
        colourValues(input, output).forEach(violations::add);
        lookTerritory(input, output).forEach(violations::add);

        return violations;
    }

    // ── individual checks ────────────────────────────────────────────────────────────────────

    private List<String> avatar(PurposePlaybookOutput output) {
        // "avatar" used in place of "persona" — 3 of 24 reference playbooks, 0 of the good ones.
        boolean used = output.bodies().stream()
            .anyMatch(b -> Pattern.compile("\\bavatars?\\b", Pattern.CASE_INSENSITIVE).matcher(b).find());
        return used
            ? List.of("The word \"avatar\" appears. The canonical term is persona, everywhere, "
                + "including in passing.")
            : List.of();
    }

    /**
     * The never-list is carried unedited — it is the negative constraint every downstream agent is
     * held to, so a dropped word silently widens what three directions are allowed to do.
     */
    private List<String> neverList(PurposePlaybookInput input, Map<String, PurposePlaybookOutput.Section> sections) {
        Object never = input.signal("belief.neverWords");
        PurposePlaybookOutput.Section section = sections.get("belief.neverList");
        if (never == null || section == null || section.body() == null) {
            return List.of();
        }
        String body = section.body().toLowerCase(Locale.ROOT);
        List<String> missing = words(never).stream()
            .filter(word -> !body.contains(word.toLowerCase(Locale.ROOT)))
            .toList();
        return missing.isEmpty()
            ? List.of()
            : List.of("The never-list has been edited: " + String.join(", ", missing)
                + " no longer appears in belief.neverList. These three words are carried verbatim.");
    }

    private List<String> outOfScopeTitles(Map<String, PurposePlaybookOutput.Section> sections) {
        List<String> violations = new ArrayList<>();
        sections.values().forEach(section -> {
            if (section.title() == null) {
                return;
            }
            String title = section.title().toLowerCase(Locale.ROOT);
            OUT_OF_SCOPE_TITLES.stream()
                .filter(title::contains)
                .forEach(term -> violations.add("Section \"" + section.key() + "\" is titled \""
                    + section.title() + "\". \"" + term + "\" is either a brand-playbook field the "
                    + "Copy agent owns or a facilitation device that must never appear as a labelled "
                    + "section."));
            if (CAUSE_ACRONYM.matcher(section.title()).find()) {
                violations.add("Section \"" + section.key() + "\" is titled \"" + section.title()
                    + "\". CAUSE is a facilitation scaffold for deriving the mission, and must never "
                    + "surface as an acronym or a labelled section in the document.");
            }
        });
        return violations;
    }

    /**
     * A colour value in the prose means the document has started designing. The one legitimate
     * exception is a colour the client themselves named — a competitor's colour they will not
     * use, or an existing one they must keep — which belongs in the look-and-feel section
     * precisely as a hard constraint, so a value that appears in the client's own answers passes.
     */
    private List<String> colourValues(PurposePlaybookInput input, PurposePlaybookOutput output) {
        String clientWords = input.signals().values().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + " " + b)
            .orElse("")
            .toLowerCase(Locale.ROOT);

        Set<String> invented = new LinkedHashSet<>();
        for (String body : output.bodies()) {
            Matcher matcher = COLOUR_VALUE.matcher(body);
            while (matcher.find()) {
                String value = matcher.group();
                if (!clientWords.contains(value.toLowerCase(Locale.ROOT))) {
                    invented.add(value);
                }
            }
        }
        return invented.isEmpty()
            ? List.of()
            : List.of("The document states colour values the client never named ("
                + String.join(", ", invented) + "). Proposing a colour pre-empts the Visual Identity "
                + "agent and biases all three directions before any direction exists — describe the "
                + "territory and its exclusions instead.");
    }

    /**
     * The look territory is what every specialist agent reads. If taste was captured and this is
     * blank, each of them is left guessing at the one thing the questionnaire went to real trouble
     * to collect.
     */
    private List<String> lookTerritory(PurposePlaybookInput input, PurposePlaybookOutput output) {
        boolean tasteCaptured = input.signals().keySet().stream().anyMatch(k -> k.startsWith("look."));
        if (!tasteCaptured) {
            return List.of();
        }
        PurposePlaybookOutput.LookTerritory territory = output.lookTerritory();
        if (territory == null || territory.posture() == null || territory.posture().isBlank()) {
            return List.of("lookTerritory is missing its posture. Taste answers were captured, and "
                + "the look-and-feel module's output contract makes this the object the logo and "
                + "visual-identity agents read — forwarding the slugs is not a territory.");
        }
        return List.of();
    }

    // ── required sections, read out of Layer 1 ───────────────────────────────────────────────

    private List<String> missingSections(Map<String, PurposePlaybookOutput.Section> sections) {
        return requiredSectionKeys(layer1Service.getModule(PurposePlaybookAgent.STRUCTURE_KEY)).stream()
            .filter(key -> !sections.containsKey(key))
            .toList();
    }

    /**
     * The required section keys, read from the structure module rather than listed here, so adding
     * a required section to Layer 1 is enough. Keys are {@code <section>} or
     * {@code <section>.<subsection>}, matching the output contract.
     *
     * <p>A pillar that declares {@code subsections:} is a container, so its own key is dropped
     * however it is marked — the document has a {@code belief.mission} body, never a
     * {@code belief} one. Requiring the container would fail every generation ever produced.
     *
     * <p>Fails open: an unrecognised module shape yields no keys and the check simply does not
     * run, because a knowledge-base edit must never be able to make every generation fail.
     */
    static List<String> requiredSectionKeys(String module) {
        if (module == null || module.isBlank()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        Set<String> containers = new LinkedHashSet<>();
        String currentSection = null;
        String pendingKey = null;

        for (String line : module.split("\n", -1)) {
            Matcher key = SECTION_KEY.matcher(line);
            if (key.matches()) {
                String name = key.group(2);
                if (key.group(1).length() <= 2) {
                    currentSection = name;
                    pendingKey = name;
                } else {
                    // A nested key proves the section above it holds subsections, whether or not
                    // the module spelled the marker out.
                    if (currentSection != null) {
                        containers.add(currentSection);
                    }
                    pendingKey = currentSection == null ? name : currentSection + "." + name;
                }
                continue;
            }
            if (SUBSECTIONS.matcher(line).matches() && currentSection != null) {
                containers.add(currentSection);
                continue;
            }
            Matcher required = REQUIRED.matcher(line);
            if (required.matches() && pendingKey != null) {
                if ("true".equals(required.group(1)) && !CONFIDENCE_SECTION.equals(pendingKey)) {
                    keys.add(pendingKey);
                }
                pendingKey = null;
            }
        }
        keys.removeAll(containers);
        return List.copyOf(keys);
    }

    /** A signal value as a list of words, whether it was stored as a list or as free text. */
    private static List<String> words(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(i -> i != null).map(String::valueOf)
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
        return List.of(String.valueOf(value).split("[,;/]")).stream()
            .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
