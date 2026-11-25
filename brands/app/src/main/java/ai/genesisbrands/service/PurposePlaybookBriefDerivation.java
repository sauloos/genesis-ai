package ai.genesisbrands.service;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.purposeplaybook.PurposePlaybookOutput;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the three direction briefs from an engagement's Purpose Playbook.
 *
 * <p>This replaces {@link BriefDerivationService} for any engagement whose questionnaire carries
 * Layer 1 signal keys. The difference is not the model or the prompt — it is what the derivation
 * reads. The legacy service reads the raw question-and-answer transcript, so it is deriving three
 * creative directions from unresolved material: contradictions unreconciled, thin answers
 * indistinguishable from considered ones, taste signals unread. The playbook has already settled
 * all of that, with its reasoning on the page and a confidence map saying which parts are thin, so
 * this derivation starts from a synthesis instead of from a pile of answers.
 *
 * <p>{@code directions/purpose-to-direction.yaml} is explicit that this step must <em>not</em> read
 * the questionnaire answers again, which is why no questions or answers reach this class at all —
 * the type signature enforces the rule rather than a comment asking for it.
 */
@Service
public class PurposePlaybookBriefDerivation {

    private static final Logger log = LoggerFactory.getLogger(PurposePlaybookBriefDerivation.class);

    /**
     * Config-only agent id — no {@code CoreAgent} bean, because this is a step inside the
     * engagement pipeline rather than a surface anyone selects.
     *
     * <p>One consequence, which {@code purpose-playbook-evaluator} does not share: that key is
     * admin-editable because {@code purpose-playbook} is a {@code CoreAgent} and
     * {@code AgentSettingsService.ownerOf} matches an {@code <id>-} prefix, while this key and
     * {@code brief-derivation} match no agent at all and so are not reachable from the agents
     * admin page. Retuning either means editing its {@code agent.yml} and releasing.
     */
    static final String AGENT_ID = "direction-brief";

    private static final String DIRECTION_KEY = "directions/purpose-to-direction.yaml";
    private static final String ARCHETYPES_KEY = "directions/archetypes.yaml";

    /** Anthropic's stop reason for a response that ran into {@code max_tokens}. */
    private static final String MAX_TOKENS_FINISH = "max_tokens";

    /**
     * The playbook sections this derivation is given, in the order they are presented.
     *
     * <p>The first two establish who the client is and are identity rather than opinion — without
     * the cover the briefs cannot even name the business. The rest are exactly the sections
     * {@code purpose-to-direction.yaml}'s input contract names, which is the point: the module
     * decides what this step reads, and widening the list means editing the module first.
     */
    private static final List<String> SECTION_KEYS = List.of(
        "cover",
        "situation",
        "strategy.positioningSummary",
        "strategy.lookAndFeel",
        "strategy.directionGuidance",
        "belief.mission",
        "belief.values",
        "belief.neverList",
        "belong.personas",
        "belong.uniqueReason",
        "belong.lineOfCircle",
        "best.valueProposition",
        "best.storyArc"
    );

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final Layer1Service layer1Service;
    private final RetrievalService retrievalService;

    public PurposePlaybookBriefDerivation(ChatClient.Builder chatClientBuilder,
                                          AgentProperties agentProperties,
                                          ObjectMapper objectMapper,
                                          Layer1Service layer1Service,
                                          RetrievalService retrievalService) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.layer1Service = layer1Service;
        this.retrievalService = retrievalService;
    }

    public List<DirectionBrief> derive(String engagementId, PurposePlaybookOutput playbook) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);

        List<String> precedent = config.getRag().isEnabled()
            ? retrievePrecedent(playbook, config.getRag().getTopK())
            : List.of();

        String userPrompt = assemblePrompt(playbook, precedent);

        // One malformed-JSON retry, for the same reason the playbook agent has one: a dropped
        // comma is very likely absent from a second sample, and this derivation is cheap next to
        // failing an engagement that has already paid for a ten-minute document.
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = generate(config, userPrompt);
            try {
                return parse(engagementId, raw);
            } catch (IllegalStateException e) {
                lastFailure = e;
                log.warn("Direction brief derivation parse failed on attempt {}/2 for engagement {}: {}",
                    attempt, engagementId, e.getMessage());
            }
        }
        throw lastFailure;
    }

    /**
     * One generation, streamed. Three full briefs is well short of the playbook's length, but the
     * reason to stream is the same: a long non-streaming request parks in {@code Mono.block()} with
     * no timeout, and a response cut off at the cap surfaces as an unhelpful JSON parse error
     * several layers away from the config that caused it.
     */
    private String generate(AgentProperties.AgentConfig config, String userPrompt) {
        List<ChatResponse> responses = chatClient.prompt()
            .system(agentProperties.systemPrompt(AGENT_ID))
            .user(userPrompt)
            .options(AnthropicChatOptions.builder()
                .model(config.getModel())
                .maxTokens(config.getMaxTokens())
                .build())
            .stream()
            .chatResponse()
            .collectList()
            .block();
        if (responses == null || responses.isEmpty()) {
            throw new IllegalStateException("The model returned an empty response.");
        }

        StringBuilder text = new StringBuilder();
        String finishReason = null;
        for (ChatResponse response : responses) {
            Generation result = response.getResult();
            if (result == null) {
                continue;
            }
            String chunk = result.getOutput() == null ? null : result.getOutput().getText();
            if (chunk != null) {
                text.append(chunk);
            }
            String reason = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
            if (reason != null && !reason.isBlank()) {
                finishReason = reason;
            }
        }

        if (MAX_TOKENS_FINISH.equalsIgnoreCase(finishReason)) {
            throw new IllegalStateException("The briefs were cut off at the " + config.getMaxTokens()
                + "-token cap after " + text.length() + " characters, so they are incomplete rather "
                + "than malformed. Raise max-tokens for " + AGENT_ID + " — retrying only samples "
                + "the same cap.");
        }
        if (text.isEmpty()) {
            throw new IllegalStateException("The model returned an empty response.");
        }
        return text.toString();
    }

    /**
     * Layer 2 precedent, queried from the playbook's own positioning rather than from a brand
     * context that does not exist yet. The module is explicit that precedent informs craft and
     * never content — how comparable engagements separated their three directions, not what those
     * directions said — so the prompt says so where the examples are presented.
     */
    private List<String> retrievePrecedent(PurposePlaybookOutput playbook, int topK) {
        String query = String.join(" ",
            body(playbook, "cover"),
            body(playbook, "strategy.positioningSummary")).strip();
        if (query.isBlank()) {
            return List.of();
        }
        return retrievalService.retrieveForAgent(query, AGENT_ID, topK);
    }

    String assemblePrompt(PurposePlaybookOutput playbook, List<String> precedent) {
        StringBuilder sb = new StringBuilder();

        sb.append("## How to derive the three directions\n\n")
            .append(layer1Service.getModule(DIRECTION_KEY)).append("\n\n");

        sb.append("## Archetypes\n\n")
            .append(layer1Service.getModule(ARCHETYPES_KEY)).append("\n\n");

        sb.append("## The client's Purpose Playbook\n\n");
        sb.append("These are the sections you derive from, and the only material you have about "
            + "this business. Each one carries its own confidence — treat a section marked thin as "
            + "thin.\n\n");
        List<String> missing = new ArrayList<>();
        for (String key : SECTION_KEYS) {
            PurposePlaybookOutput.Section section = playbook.section(key);
            if (section == null || section.body() == null || section.body().isBlank()) {
                missing.add(key);
                continue;
            }
            sb.append("### ").append(key);
            if (section.title() != null && !section.title().isBlank()) {
                sb.append(" — ").append(section.title());
            }
            if (section.confidence() != null && !section.confidence().isBlank()) {
                sb.append(" (confidence: ").append(section.confidence()).append(")");
            }
            sb.append("\n").append(section.body().strip()).append("\n\n");
        }

        if (!missing.isEmpty()) {
            // A section the playbook did not write is a real gap and has to be visible, or the
            // model fills it silently. Several of these sections are legitimately optional.
            sb.append("### Sections not written in this playbook\n");
            sb.append("Derive nothing for these. Where a direction depends on one, say so in its "
                + "risk rather than inventing the content:\n");
            missing.forEach(key -> sb.append("- ").append(key).append("\n"));
            sb.append("\n");
        }

        appendLookTerritory(sb, playbook.lookTerritory());
        appendConfidence(sb, playbook.confidence());

        if (!precedent.isEmpty()) {
            sb.append("## Precedent — how comparable engagements separated their directions\n\n");
            sb.append("Craft reference only. Never carry a precedent's content, names, figures or "
                + "positioning into this client's briefs.\n\n");
            for (int i = 0; i < precedent.size(); i++) {
                sb.append("Example ").append(i + 1).append(":\n").append(precedent.get(i)).append("\n\n");
            }
        }

        sb.append("Now derive the three briefs. Return the JSON object described in your "
            + "instructions and nothing else.\n");
        return sb.toString();
    }

    /**
     * The typed look territory, which the visual identity and logo agents read as fields rather
     * than prose. It is in {@code strategy.lookAndFeel} as well, but the hard parts — the forbidden
     * palette, the posture, and the tensions between what the client said and what they picked —
     * are the ones a paragraph is most likely to soften, so they are restated here as data.
     */
    private void appendLookTerritory(StringBuilder sb, PurposePlaybookOutput.LookTerritory look) {
        if (look == null) {
            return;
        }
        sb.append("## The look-and-feel territory, as data\n\n");
        appendField(sb, "Summary words", String.join(", ", look.summaryWords()));
        appendField(sb, "Posture", look.posture());
        appendField(sb, "Palette", look.palette());
        appendField(sb, "Typography", look.typography());
        appendField(sb, "Logo intent", look.logo());
        appendField(sb, "Imagery", look.imagery());
        appendField(sb, "Confidence", look.confidence());
        if (!look.tensions().isEmpty()) {
            sb.append("\nTensions the client's own answers contain. The module says to state these "
                + "rather than average them away — they are often what separates the three "
                + "directions:\n");
            look.tensions().forEach(t -> sb.append("- ").append(t).append("\n"));
        }
        sb.append("\n");
    }

    private void appendConfidence(StringBuilder sb, List<PurposePlaybookOutput.Gap> confidence) {
        if (confidence.isEmpty()) {
            return;
        }
        sb.append("## Where the playbook is unsure\n\n");
        sb.append("A direction that leans its whole bet on a field listed as thin here is a guess "
            + "dressed as a recommendation. Lean on the confident fields, and name the thin one in "
            + "the risk when a direction has to use it.\n\n");
        for (PurposePlaybookOutput.Gap gap : confidence) {
            sb.append("- ").append(gap.field()).append(": ").append(gap.confidence());
            if (gap.whatWouldRaiseIt() != null && !gap.whatWouldRaiseIt().isBlank()) {
                sb.append(" — would be raised by: ").append(gap.whatWouldRaiseIt());
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    List<DirectionBrief> parse(String engagementId, String raw) {
        Map<String, Object> map;
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to parse direction briefs: " + e.getMessage() + "\nRaw: " + raw, e);
        }

        Map<String, Object> fn = (Map<String, Object>) map.get("foundation");
        DirectionBrief.BrandFoundation foundation = new DirectionBrief.BrandFoundation(
            str(value(fn, "differentiator")),
            str(value(fn, "targetAudiencePersona")),
            str(value(fn, "corePositioning")),
            str(value(fn, "toneSpectrum"))
        );
        List<String> invariants = strings(map.get("invariants"));
        String nameBlock = nameBlock(map.get("name"));

        Object rawDirections = map.get("directions");
        if (!(rawDirections instanceof List<?> list)) {
            throw new IllegalStateException("No directions array in the derivation output.\nRaw: " + raw);
        }

        Map<DirectionBrief.CreativeDirection, DirectionBrief> byDirection = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            DirectionBrief brief = brief(engagementId, foundation, invariants, nameBlock, (Map<String, Object>) m);
            byDirection.put(brief.direction(), brief);
        }

        // All three, exactly once. A derivation that returned two directions, or the same one
        // twice, must not quietly produce a two-direction engagement: the client is promised a
        // choice of three, and every downstream surface counts on all three being present.
        List<DirectionBrief> briefs = new ArrayList<>();
        for (DirectionBrief.CreativeDirection direction : DirectionBrief.CreativeDirection.values()) {
            DirectionBrief brief = byDirection.get(direction);
            if (brief == null) {
                throw new IllegalStateException("The derivation is missing the " + direction
                    + " direction — got " + byDirection.keySet() + ".\nRaw: " + raw);
            }
            briefs.add(brief);
        }
        return briefs;
    }

    @SuppressWarnings("unchecked")
    private DirectionBrief brief(String engagementId, DirectionBrief.BrandFoundation foundation,
                                 List<String> invariants, String nameBlock, Map<String, Object> d) {
        DirectionBrief.CreativeDirection direction = direction(str(d.get("direction")));

        DirectionBrief.PurposeDerivation purpose = new DirectionBrief.PurposeDerivation(
            str(d.get("summary")),
            str(d.get("tension")),
            str(d.get("archetype")),
            str(d.get("tonalRegister")),
            str(d.get("visualTerritory")),
            strings(d.get("retains")),
            strings(d.get("releases")),
            str(d.get("audienceEmphasis")),
            str(d.get("bet")),
            str(d.get("risk")),
            invariants,
            strings(d.get("derivedFrom"))
        );

        Map<String, Object> b = (Map<String, Object>) d.get("brand");
        String audience = purpose.audienceEmphasis() == null || purpose.audienceEmphasis().isBlank()
            ? foundation.targetAudiencePersona()
            : purpose.audienceEmphasis();
        DirectionBrief.BrandContext brand = new DirectionBrief.BrandContext(
            BrandNames.tradingName(str(value(b, "name"))),
            str(value(b, "industry")),
            audience,
            str(value(b, "coreOffer")),
            foundation.differentiator(),
            strings(value(b, "personality")),
            str(value(b, "tone"))
        );

        return new DirectionBrief(engagementId, brand, foundation, direction,
            strings(d.get("trainingInstructions")), render(purpose) + nameBlock, purpose);
    }

    /**
     * What the name means and which versions of it the brand may use, appended to every
     * direction's {@code additionalContext}: the logo is built on the founder's meaning and drawn
     * across the versions, and the copy must not contradict either. Empty when neither is known.
     */
    @SuppressWarnings("unchecked")
    static String nameBlock(Object rawName) {
        if (!(rawName instanceof Map<?, ?> m)) {
            return "";
        }
        Map<String, Object> name = (Map<String, Object>) m;
        StringBuilder sb = new StringBuilder();
        String meaning = str(name.get("meaning"));
        if (meaning != null && !meaning.isBlank() && !"null".equals(meaning.strip())) {
            sb.append("Why the brand is called what it is (the founder's meaning — build on it, "
                + "never on a reading they did not intend): ").append(meaning.strip()).append("\n\n");
        }
        List<String> versions = strings(name.get("versions")).stream()
            .map(BrandNames::tradingName)
            .filter(v -> v != null && !v.isBlank())
            .distinct()
            .toList();
        if (!versions.isEmpty()) {
            sb.append("Versions of the name the brand may use (a descriptor is always secondary to "
                + "the name; never a legal suffix):\n");
            versions.forEach(v -> sb.append("- ").append(v).append("\n"));
        }
        return sb.isEmpty() ? "" : "\n\n" + sb.toString().strip();
    }

    /**
     * The derivation rendered as the brief's {@code additionalContext}.
     *
     * <p>Every specialist agent already reads that field, so this is what makes the richer
     * derivation reach Copy, Visual Identity, Logo, Playbook and Brand Book without touching any
     * of them. The typed {@code purpose} record carries the same material for anything that wants
     * the fields; this is the copy the five existing prompts actually see.
     */
    static String render(DirectionBrief.PurposeDerivation purpose) {
        StringBuilder sb = new StringBuilder();
        sb.append("Derived from this client's Purpose Playbook.\n\n");
        line(sb, "Direction in one sentence", purpose.summary());
        line(sb, "The tension it resolves", purpose.tension());
        line(sb, "Primary archetype", purpose.archetype());
        line(sb, "Tonal register", purpose.tonalRegister());
        line(sb, "Visual territory", purpose.visualTerritory());
        line(sb, "Audience emphasis", purpose.audienceEmphasis());
        line(sb, "What it bets on", purpose.bet());
        line(sb, "What it risks", purpose.risk());
        bullets(sb, "Retains", purpose.retains());
        bullets(sb, "Releases", purpose.releases());
        bullets(sb, "Constraints that hold across all three directions — do not break these",
            purpose.invariants());
        bullets(sb, "Derived from playbook sections", purpose.derivedFrom());
        return sb.toString().strip();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value.strip()).append("\n\n");
        }
    }

    private static void bullets(StringBuilder sb, String label, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        values.forEach(v -> sb.append("- ").append(v).append("\n"));
        sb.append("\n");
    }

    private static DirectionBrief.CreativeDirection direction(String name) {
        if (name == null) {
            throw new IllegalStateException("A derived direction has no direction key.");
        }
        try {
            return DirectionBrief.CreativeDirection.valueOf(name.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown direction key: " + name, e);
        }
    }

    private String body(PurposePlaybookOutput playbook, String key) {
        PurposePlaybookOutput.Section section = playbook.section(key);
        return section == null || section.body() == null ? "" : section.body();
    }

    private static Object value(Map<String, Object> map, String key) {
        return map == null ? null : map.get(key);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(i -> i != null).map(String::valueOf).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }
}
