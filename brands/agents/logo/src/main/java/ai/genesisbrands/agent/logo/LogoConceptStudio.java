package ai.genesisbrands.agent.logo;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.platform.knowledge.KnowledgeCases;
import ai.genesisbrands.service.Layer1Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The concept stage before anything is drawn, run the way a studio works: a designer fills a
 * sketchbook with ideas in words (config key {@code logo-concepts}), each built from a concrete
 * fact mined from the brief with a named technique from the Layer 1 logo-craft module; then the
 * creative director reviews them in words (config key {@code logo-concept-critic}) and pins up
 * only the strongest few, with drawing notes. Drawing is expensive and the image model cannot
 * have ideas, so the ideas are fought over here.
 */
@Service
public class LogoConceptStudio {

    private static final Logger log = LoggerFactory.getLogger(LogoConceptStudio.class);
    static final String AGENT_ID_CONCEPTS = "logo-concepts";
    static final String AGENT_ID_CRITIC = "logo-concept-critic";
    static final String CRAFT_KEY = "directions/logo-craft.yaml";
    private static final String RUBRIC_KEY = "evaluation/logo-rubric.yaml";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final Layer1Service layer1Service;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<KnowledgeCases> knowledgeCases;

    /** Approved cases from the case library shown to the designer and the critic. */
    @Value("${genesis.logo.cases:12}")
    private int caseCount = 12;

    /** Concepts written per round of ideation. */
    @Value("${genesis.logo.concepts:10}")
    private int conceptCount = 10;

    /** Concepts the critic sends on to be drawn. */
    @Value("${genesis.logo.drawn-concepts:2}")
    private int drawnConcepts = 2;

    public LogoConceptStudio(ChatClient.Builder chatClientBuilder,
                             AgentProperties agentProperties,
                             Layer1Service layer1Service,
                             ObjectMapper objectMapper,
                             ObjectProvider<KnowledgeCases> knowledgeCases) {
        this.knowledgeCases = knowledgeCases;
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.layer1Service = layer1Service;
        this.objectMapper = objectMapper;
    }

    /** A concept the critic chose, with its drawing notes. */
    public record Concept(Map<String, Object> fields, String why, String drawingNotes) {

        public String name() { return str(fields, "name"); }
        public String technique() { return str(fields, "technique"); }
        /** The version of the brand name this concept sets, e.g. "One Way Coffee Roasters". */
        public String nameVersion() { return str(fields, "nameVersion"); }

        /** WORDMARK when the name itself is the logo, so there is no separate symbol to cut. */
        public boolean wordmark() {
            String form = str(fields, "form");
            return form != null && form.strip().toUpperCase().startsWith("WORDMARK");
        }

        /** The concept as the art director reads it. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            line(sb, "Name", name());
            line(sb, "Idea", str(fields, "idea"));
            line(sb, "Built from", str(fields, "insight"));
            line(sb, "Technique", technique());
            line(sb, "Form", wordmark() ? "WORDMARK" : "LOCKUP");
            line(sb, "Name version to set", nameVersion());
            line(sb, "Hierarchy", str(fields, "hierarchy"));
            line(sb, "Arrangement", str(fields, "arrangement"));
            line(sb, "Description", str(fields, "description"));
            line(sb, "Icon", str(fields, "icon"));
            line(sb, "Why it was chosen", why);
            line(sb, "Drawing notes", drawingNotes);
            return sb.toString();
        }
    }

    /**
     * Writes concepts for this direction and returns the ones worth drawing, best first. With
     * {@code earlierReview} (the creative director's verdict on drawn work), the new concepts
     * must answer it. Throws IllegalStateException when the designer's output is unusable.
     */
    public List<Concept> develop(DirectionBrief brief, LogoIdentity identity, String briefPrompt,
                                 String earlierReview) {
        String craft = module(CRAFT_KEY);
        String taken = takenByOtherDirections(brief) + casesBlock(brief);
        Ideas ideas = ideate(briefPrompt + taken, craft, earlierReview);
        Review review = critique(brief, identity, craft, taken, ideas, true);

        if (review != null && review.revise()) {
            log.info("Logo concept review for {} direction of engagement {} asked for new ideas: {}",
                brief.direction(), brief.engagementId(), review.feedback());
            Ideas second = ideate(briefPrompt + taken, craft, conceptFeedback(ideas, review));
            Review again = critique(brief, identity, craft, taken, second, false);
            if (again != null || review.chosen().isEmpty()) {
                ideas = second;
                review = again;
            }
        }
        List<Concept> out = chosen(ideas, review);
        log.info("Logo concepts for {} direction of engagement {}: drawing {}", brief.direction(),
            brief.engagementId(), out.stream().map(c -> c.name() + " (" + c.technique() + ", "
                + c.nameVersion() + ")").toList());
        return out;
    }

    // ── Case library ──────────────────────────────────────────────────────────

    /**
     * Approved logo cases from the agency's case library, closest to this brand's sector first.
     * Empty when the app has no library or nothing is approved yet.
     */
    String casesBlock(DirectionBrief brief) {
        KnowledgeCases library = knowledgeCases.getIfAvailable();
        if (library == null || caseCount <= 0) return "";
        String hint = brief.brand() == null ? "" : brief.brand().industry() + " " + brief.brand().coreOffer();
        List<KnowledgeCases.Case> cases;
        try {
            cases = library.approved("logo", hint, caseCount);
        } catch (RuntimeException e) {
            log.warn("Could not read the logo case library: {}", e.getMessage());
            return "";
        }
        if (!cases.isEmpty()) log.info("Logo concepts read {} approved library case(s)", cases.size());
        return LogoCases.render(cases);
    }

    // ── Spread across directions ──────────────────────────────────────────────

    /**
     * The logo each direction of an engagement ended up with, so the next direction is told what
     * is already taken: a client choosing between three logos that look alike has no choice. The
     * pipeline runs the directions one after another, so this sees the earlier ones. Held in
     * memory and bounded; a regeneration after a restart simply runs without it.
     */
    private final Map<String, Map<DirectionBrief.CreativeDirection, String>> winners =
        java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<DirectionBrief.CreativeDirection, String>> e) {
                return size() > 200;
            }
        });

    /** Records the logo the creative director chose for this direction. */
    public void recordWinner(DirectionBrief brief, Concept concept, String drawnAs) {
        if (brief.engagementId() == null) return;
        StringBuilder sb = new StringBuilder();
        if (concept != null) {
            line(sb, "Concept", concept.name());
            line(sb, "Idea", str(concept.fields(), "idea"));
            line(sb, "Technique", concept.technique());
            line(sb, "Form", concept.wordmark() ? "WORDMARK" : "LOCKUP");
            line(sb, "Name version", concept.nameVersion());
            line(sb, "Arrangement", str(concept.fields(), "arrangement"));
        }
        line(sb, "As drawn", drawnAs);
        if (sb.isEmpty()) return;
        winners.computeIfAbsent(brief.engagementId(), k -> new java.util.concurrent.ConcurrentHashMap<>())
            .put(brief.direction(), sb.toString());
    }

    /** The other directions' logos as a prompt block, or "" when there are none yet. */
    String takenByOtherDirections(DirectionBrief brief) {
        if (brief.engagementId() == null) return "";
        Map<DirectionBrief.CreativeDirection, String> taken = winners.get(brief.engagementId());
        if (taken == null) return "";
        StringBuilder sb = new StringBuilder();
        taken.forEach((direction, logo) -> {
            if (direction != brief.direction()) {
                sb.append("### ").append(direction.name()).append("\n").append(logo).append("\n");
            }
        });
        if (sb.isEmpty()) return "";
        return "## Logos the other directions of this engagement already have\n"
            + "The client will see all three directions side by side. This direction's logo must be "
            + "clearly different from these at a glance: a different idea, or the same root "
            + "transformed by a different technique into a different form. Never the same sign, "
            + "device or layout again.\n\n" + sb + "\n";
    }

    // ── Ideation ──────────────────────────────────────────────────────────────

    private record Ideas(String raw, Map<Integer, Map<String, Object>> concepts, List<String> insights) {}

    private Ideas ideate(String briefPrompt, String craft, String review) {
        StringBuilder sb = new StringBuilder();
        if (!craft.isBlank()) sb.append("## Logo Craft (Layer 1 module — authoritative)\n").append(craft).append("\n\n");
        sb.append(briefPrompt);
        if (review != null && !review.isBlank()) sb.append(review).append("\n");
        sb.append("## Task\nWrite ").append(conceptCount).append(" logo concepts for this direction.\n");

        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID_CONCEPTS);
        String raw = call(AGENT_ID_CONCEPTS, config, sb.toString());
        Map<String, Object> map;
        try {
            map = readJson(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse logo concepts: " + e.getMessage(), e);
        }
        Map<Integer, Map<String, Object>> concepts = new LinkedHashMap<>();
        if (map.get("concepts") instanceof List<?> list) {
            int fallbackId = 1;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) m;
                int id = c.get("id") instanceof Number n ? n.intValue() : fallbackId;
                concepts.putIfAbsent(id, c);
                fallbackId = Math.max(fallbackId, id) + 1;
            }
        }
        if (concepts.isEmpty()) throw new IllegalStateException("The logo concept stage returned no concepts");
        List<String> insights = map.get("insights") instanceof List<?> l
            ? l.stream().map(String::valueOf).toList() : List.of();
        return new Ideas(raw, concepts, insights);
    }

    // ── Critique ──────────────────────────────────────────────────────────────

    private record Pick(int id, String why, String drawingNotes) {}

    private record Review(boolean revise, List<Pick> chosen, String rejected, String feedback) {}

    /** The creative director's review; null when it failed, which never fails the logo. */
    private Review critique(DirectionBrief brief, LogoIdentity identity, String craft, String taken,
                            Ideas ideas, boolean allowRevise) {
        StringBuilder sb = new StringBuilder();
        if (!craft.isBlank()) sb.append("## Logo Craft (Layer 1 module — authoritative)\n").append(craft).append("\n\n");
        sb.append("## Logo Rubric\n").append(module(RUBRIC_KEY)).append("\n\n");
        sb.append("## Creative Direction: ").append(brief.direction().name()).append("\n\n");
        DirectionBrief.BrandContext b = brief.brand();
        sb.append("## Brand Context\n")
            .append("Name: ").append(b.name()).append("\n")
            .append("Industry: ").append(b.industry()).append("\n")
            .append("Target audience: ").append(b.targetAudience()).append("\n")
            .append("Core offer: ").append(b.coreOffer()).append("\n")
            .append("Differentiator: ").append(b.differentiator()).append("\n")
            .append("Personality: ").append(String.join(", ", b.personality())).append("\n")
            .append("Tone: ").append(b.tone()).append("\n\n");
        if (identity != null && !identity.render().isBlank()) {
            sb.append("## Visual Identity (same direction)\n").append(identity.render()).append("\n");
        }
        if (brief.additionalContext() != null && !brief.additionalContext().isBlank()) {
            sb.append("## Additional Context\n").append(brief.additionalContext()).append("\n\n");
        }
        sb.append(taken);
        sb.append("## The Logo Agent's concepts\n").append(ideas.raw()).append("\n\n");
        sb.append("## Task\nChoose up to ").append(drawnConcepts).append(" concepts to draw, best first.\n");
        sb.append(allowRevise
            ? "You may return REVISE if no concept is good enough to show a paying client.\n"
            : "This is the final round: return ACCEPT with the strongest concepts.\n");

        try {
            AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID_CRITIC);
            Map<String, Object> map = readJson(call(AGENT_ID_CRITIC, config, sb.toString()));
            List<Pick> picks = new ArrayList<>();
            if (map.get("chosen") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && m.get("id") instanceof Number n
                        && ideas.concepts().containsKey(n.intValue())) {
                        picks.add(new Pick(n.intValue(), strOf(m.get("why")), strOf(m.get("drawingNotes"))));
                    }
                }
            }
            boolean revise = allowRevise && "REVISE".equalsIgnoreCase(String.valueOf(map.get("verdict")));
            String rejected = map.get("rejected") == null ? null : objectMapper.writeValueAsString(map.get("rejected"));
            return new Review(revise, picks, rejected, strOf(map.get("feedback")));
        } catch (Exception e) {
            log.warn("Logo concept review failed for engagement {}; drawing the first concepts: {}",
                brief.engagementId(), e.getMessage());
            return null;
        }
    }

    private List<Concept> chosen(Ideas ideas, Review review) {
        int limit = Math.max(1, drawnConcepts);
        List<Concept> out = new ArrayList<>();
        if (review != null) {
            for (Pick p : review.chosen()) {
                if (out.size() >= limit) break;
                if (out.stream().anyMatch(c -> c.fields() == ideas.concepts().get(p.id()))) continue;
                out.add(new Concept(ideas.concepts().get(p.id()), p.why(), p.drawingNotes()));
            }
        }
        for (Map<String, Object> c : ideas.concepts().values()) {
            if (out.size() >= limit || !out.isEmpty() && review != null) break;
            out.add(new Concept(c, null, null));
        }
        return out;
    }

    private static String conceptFeedback(Ideas ideas, Review review) {
        StringBuilder sb = new StringBuilder("## Creative Director's review of your concepts\n");
        sb.append("No concept in your first set is ready to draw. Write a completely new set that answers ")
            .append("this review; do not resubmit the rejected ideas in new clothes.\n\n");
        if (review.feedback() != null) sb.append("Review: ").append(review.feedback()).append("\n");
        if (review.rejected() != null) sb.append("Rejected: ").append(review.rejected()).append("\n");
        if (!ideas.insights().isEmpty()) {
            sb.append("Insights you mined last time (reuse or replace): ")
                .append(String.join("; ", ideas.insights())).append("\n");
        }
        return sb.append("\n").toString();
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private String call(String key, AgentProperties.AgentConfig config, String user) {
        return chatClient.prompt()
            .system(agentProperties.systemPrompt(key))
            .user(user)
            .options(AnthropicChatOptions.builder()
                .model(config.getModel())
                .maxTokens(config.getMaxTokens())
                .build())
            .call()
            .content();
    }

    private String module(String key) {
        try {
            return layer1Service.getModule(key);
        } catch (IllegalStateException e) {
            log.warn("Layer 1 module {} is missing; the logo concept stage runs without it", key);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String raw) throws Exception {
        String json = raw.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }
        return objectMapper.reader()
            .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .readValue(json, Map.class);
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append(label).append(": ").append(value).append("\n");
    }

    private static String str(Map<String, Object> map, String key) {
        return strOf(map.get(key));
    }

    private static String strOf(Object v) {
        return v == null ? null : v.toString();
    }
}
