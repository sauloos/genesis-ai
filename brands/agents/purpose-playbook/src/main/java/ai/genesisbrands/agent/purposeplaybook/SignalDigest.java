package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.lookkit.LookKit;
import ai.genesisbrands.lookkit.LookKitCatalogue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw signal map into something a model can actually reason over, and says what is
 * missing from it.
 *
 * <p>Three jobs, none of which belong in the prompt template:
 *
 * <ol>
 *   <li><b>Regroup the flattened repeats.</b> The questionnaire engine has no repeat primitive,
 *       so three personas arrive as {@code belong.persona[0].story},
 *       {@code belong.persona[1].story} and so on. Handed to a model in question order those
 *       interleave with everything else; grouped, each persona reads as one block.</li>
 *   <li><b>Resolve the look-and-feel slugs.</b> A client's taste answers are stored as values —
 *       {@code mark-organic}, {@code warm-earth}. {@link LookKit} turns those back into the
 *       design term, the real hexes and the face, because a brief that forwards eight slugs has
 *       done no work. Note that this resolves <em>facts</em> only; what a choice means is
 *       Layer 1's job and reaches the model as the {@code look-and-feel-kit} module text.</li>
 *   <li><b>Say what is absent, and why.</b> Three different absences, which the confidence map
 *       must not conflate: a signal the client was asked for and skipped, a signal the structure
 *       module wants that this questionnaire never collected at all, and a signal answered so
 *       briefly that nothing can safely be derived from it.</li>
 * </ol>
 *
 * <p>Slider positions stay factual here ("4 of 5"). The 1–5 reading, and the rule that a 3 means
 * no preference rather than a midpoint to design to, live in
 * {@code directions/look-and-feel-kit.yaml} — interpretation must never be hard-coded beside the
 * kit.
 */
@Component
public class SignalDigest {

    /**
     * A signal key as it appears anywhere — a {@code from:} list, a {@code condition:}, or prose.
     * Namespaces are closed (the seven Layer 1 pillars), so this cannot drift onto an ordinary
     * dotted word. Ends on a non-dot so a sentence-final full stop is not swallowed.
     */
    private static final Pattern SIGNAL_REF = Pattern.compile(
        "\\b(?:setup|belief|belong|best|beyond|look|reflect)"
            + "(?:\\.[A-Za-z0-9_*]+|\\[\\d+\\])*"
            + "(?:\\.[A-Za-z0-9_*]+|\\[\\d+\\])");

    /** The {@code [0]} in {@code belong.persona[0].story} — an instance, not part of the key. */
    private static final Pattern INDEX = Pattern.compile("\\[\\d+\\]");

    /** An answer of this many words or fewer carries too little to derive a playbook field from. */
    private static final int TERSE_WORDS = 3;

    private final LookKit lookKit;
    private final ObjectMapper objectMapper;

    public SignalDigest(LookKit lookKit, ObjectMapper objectMapper) {
        this.lookKit = lookKit;
        this.objectMapper = objectMapper;
    }

    /**
     * @param rendered      the signals as grouped markdown, for the prompt
     * @param terse         keys answered in {@value #TERSE_WORDS} words or fewer
     * @param skipped       keys the questionnaire asked for on this response and got no answer to
     * @param notCollected  keys the structure module derives from that this questionnaire never
     *                      asked — a gap in the instrument, not in the client
     */
    public record Digest(String rendered, List<String> terse, List<String> skipped,
                         List<String> notCollected) {

        public boolean hasGaps() {
            return !terse.isEmpty() || !skipped.isEmpty() || !notCollected.isEmpty();
        }
    }

    public Digest of(PurposePlaybookInput input, String structureModule) {
        Map<String, Object> signals = input.signals();

        List<String> terse = signals.entrySet().stream()
            .filter(e -> isTerse(e.getValue()))
            .map(Map.Entry::getKey)
            .toList();

        List<String> skipped = input.expectedSignals().stream()
            .filter(k -> !signals.containsKey(k))
            .distinct()
            .toList();

        return new Digest(render(signals), terse, skipped,
            notCollected(structureModule, signals.keySet(), input.expectedSignals()));
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────

    private String render(Map<String, Object> signals) {
        // pillar → instance ("" when the key is not part of a repeat) → rendered lines
        Map<String, Map<String, List<String>>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : signals.entrySet()) {
            String key = entry.getKey();
            String pillar = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
            grouped.computeIfAbsent(pillar, p -> new LinkedHashMap<>())
                .computeIfAbsent(instanceOf(key), i -> new ArrayList<>())
                .add("- " + key + ": " + describe(key, entry.getValue()));
        }

        StringBuilder sb = new StringBuilder();
        grouped.forEach((pillar, instances) -> {
            sb.append("### ").append(pillar).append("\n");
            instances.forEach((instance, lines) -> {
                if (!instance.isEmpty()) {
                    sb.append("\n").append(instance).append("\n");
                }
                lines.forEach(line -> sb.append(line).append("\n"));
            });
            sb.append("\n");
        });
        return sb.toString();
    }

    /**
     * The repeat instance a key belongs to — {@code belong.persona[0]} for
     * {@code belong.persona[0].story} — or the empty string for an ordinary key. A key that ends
     * in an index ({@code beyond.visionBoardDetail[2]}) is a value in its own right, not a block,
     * so it stays ungrouped.
     */
    static String instanceOf(String key) {
        Matcher matcher = INDEX.matcher(key);
        if (!matcher.find()) {
            return "";
        }
        int end = matcher.end();
        return end == key.length() ? "" : key.substring(0, end);
    }

    /** Facts about a stored value, with look-and-feel slugs resolved through the kit. */
    String describe(String key, Object value) {
        if (value == null) {
            return "(no answer)";
        }
        if (value instanceof Collection<?> items) {
            if (items.isEmpty()) {
                return "(nothing selected)";
            }
            return items.stream().map(item -> describe(key, item)).reduce((a, b) -> a + "; " + b).orElse("");
        }
        if (value instanceof Map<?, ?> map) {
            // An upload answer: the bytes are elsewhere, the fact that one exists is the signal.
            Object name = map.get("name");
            if (name != null) {
                Object contentType = map.get("contentType");
                return "uploaded file \"" + name + "\" ("
                    + (contentType == null ? "unknown type" : contentType) + ")";
            }
            return json(map);
        }
        if (value instanceof Number number) {
            return key.contains(".slider.") ? number + " of 5" : number.toString();
        }
        String text = String.valueOf(value);
        return key.startsWith("look.") ? resolveLook(text) : text;
    }

    /**
     * A look-kit value as the design term behind it. Falls through to the raw text for the look
     * answers that are free text rather than a tap ({@code look.mustKeep},
     * {@code look.palette.forbidden}, an admired brand's name).
     */
    private String resolveLook(String value) {
        Optional<LookKitCatalogue.Palette> palette = lookKit.palette(value);
        if (palette.isPresent()) {
            LookKitCatalogue.Palette p = palette.get();
            return p.label() + " — " + p.note() + " [" + rolesOf(p) + "]";
        }
        Optional<LookKitCatalogue.Tile> tile = lookKit.tile(value);
        if (tile.isPresent()) {
            return tile.get().label() + " (" + tile.get().form() + ")";
        }
        Optional<LookKitCatalogue.TypeSpecimen> specimen = lookKit.typeSpecimen(value);
        return specimen
            .map(s -> s.label() + " (" + s.form() + ", shown as " + s.fontFamily() + ")")
            .orElse(value);
    }

    /** A palette's colours addressed by job, so no agent has to guess a role from a position. */
    private String rolesOf(LookKitCatalogue.Palette palette) {
        Map<String, String> roles = palette.roles();
        if (roles == null || roles.isEmpty()) {
            return String.join(", ", palette.colors());
        }
        return roles.entrySet().stream()
            .map(e -> e.getKey() + " " + e.getValue())
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    // ── absence ──────────────────────────────────────────────────────────────────────────────

    private boolean isTerse(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String trimmed = text.strip();
        return !trimmed.isEmpty() && trimmed.split("\\s+").length <= TERSE_WORDS;
    }

    /**
     * Signals the structure module derives sections from that this questionnaire did not collect.
     *
     * <p>Read out of the Layer 1 module text rather than listed here, so the module stays the
     * single source of truth for what the document needs: add a {@code from:} entry to a section
     * and this notices, with no Java change. A wildcard ({@code belief.cause.*}) counts as
     * collected when anything under that prefix was asked for, and a concrete reference matches a
     * repeat instance ({@code belong.persona.decisionDriver} against
     * {@code belong.persona[1].decisionDriver}) because the index is an instance, not part of the
     * contract.
     */
    List<String> notCollected(String structureModule, Set<String> answered, Collection<String> expected) {
        if (structureModule == null || structureModule.isBlank()) {
            return List.of();
        }
        Set<String> present = new LinkedHashSet<>();
        answered.forEach(k -> present.add(INDEX.matcher(k).replaceAll("")));
        expected.forEach(k -> present.add(INDEX.matcher(k).replaceAll("")));

        Set<String> absent = new LinkedHashSet<>();
        Matcher matcher = SIGNAL_REF.matcher(structureModule);
        while (matcher.find()) {
            String reference = INDEX.matcher(matcher.group()).replaceAll("");
            if (!isCollected(reference, present)) {
                absent.add(matcher.group());
            }
        }
        return List.copyOf(absent);
    }

    private boolean isCollected(String reference, Set<String> present) {
        if (reference.endsWith(".*")) {
            String prefix = reference.substring(0, reference.length() - 1);
            return present.stream().anyMatch(p -> p.startsWith(prefix));
        }
        // A reference may name a parent of what was collected (`belong.persona` for
        // `belong.persona.story`) as readily as a leaf.
        return present.contains(reference)
            || present.stream().anyMatch(p -> p.startsWith(reference + "."));
    }
}
