package ai.genesisbrands.agent.logo;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.platform.OpenAiImageClient;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.RetrievalService;
import ai.genesisbrands.usage.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Designs one direction's logo.
 *
 * <p>For the image methods (DALLE = the OpenAI/Azure gpt-image model, IDEOGRAM) it builds a small
 * logo system the way a studio would: the agent writes the concept and an art-directed prompt for
 * the primary lockup (symbol + wordmark, set in the direction's own typeface and palette); the
 * image model draws several candidates on a transparent background; Genesis AI looks at all of
 * them and picks one, or sends the concept back once if none is good enough; and the symbol is
 * then cut from the chosen lockup by an edit, so the icon and the lockup always match.
 *
 * <p>SVG_CONCEPT still returns hand-written SVG markup in one step.
 */
@Service
public class LogoAgent {

    private static final Logger log = LoggerFactory.getLogger(LogoAgent.class);
    private static final String AGENT_ID_DALLE = "logo-dalle";
    private static final String AGENT_ID_SVG = "logo-svg";
    private static final String AGENT_ID_IDEOGRAM = "logo-ideogram";
    private static final String ASSET_URL_PREFIX = "/api/assets/";

    private final ChatClient chatClient;
    private final OpenAiImageClient openAiImages;
    private final IdeogramImageClient ideogramImages;
    private final LogoEvaluator evaluator;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final BlobStorageService blobStorageService;
    private final RetrievalService retrievalService;
    private final UsageService usageService;

    /** Lockups drawn per round. */
    @Value("${genesis.logo.candidates:3}")
    private int candidatesPerRound = 3;

    /** Extra rounds the selector may ask for when no candidate is good enough. */
    @Value("${genesis.logo.selection-rounds:1}")
    private int extraRounds = 1;

    public LogoAgent(ChatClient.Builder chatClientBuilder,
                     OpenAiImageClient openAiImages,
                     IdeogramImageClient ideogramImages,
                     LogoEvaluator evaluator,
                     AgentProperties agentProperties,
                     ObjectMapper objectMapper,
                     BlobStorageService blobStorageService,
                     RetrievalService retrievalService,
                     UsageService usageService) {
        this.chatClient = chatClientBuilder.build();
        this.openAiImages = openAiImages;
        this.ideogramImages = ideogramImages;
        this.evaluator = evaluator;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.blobStorageService = blobStorageService;
        this.retrievalService = retrievalService;
        this.usageService = usageService;
    }

    // ── Public interface (defines the SpecialistAgent contract) ───────────────

    public LogoOutput execute(DirectionBrief brief, LogoOutput.Method method) {
        return execute(brief, method, null);
    }

    public LogoOutput execute(DirectionBrief brief, LogoOutput.Method method, LogoIdentity identity) {
        return run(brief, method, identity, null, null, 1);
    }

    public LogoOutput executeWithRevision(DirectionBrief brief, LogoOutput previous, AgentRevision revision) {
        return executeWithRevision(brief, previous, revision, null);
    }

    public LogoOutput executeWithRevision(DirectionBrief brief, LogoOutput previous, AgentRevision revision,
                                          LogoIdentity identity) {
        return run(brief, previous.method(), identity, previous, revision, previous.iteration() + 1);
    }

    private LogoOutput run(DirectionBrief brief, LogoOutput.Method method, LogoIdentity identity,
                           LogoOutput previous, AgentRevision revision, int iteration) {
        String agentId = switch (method) {
            case DALLE -> AGENT_ID_DALLE;
            case IDEOGRAM -> AGENT_ID_IDEOGRAM;
            case SVG_CONCEPT -> AGENT_ID_SVG;
        };
        AgentProperties.AgentConfig config = agentProperties.get(agentId);

        DirectionBrief.BrandContext b = brief.brand();
        List<String> precedent = config.getRag().isEnabled()
            ? retrievalService.retrieveForAgent(
                String.join(" ", b.name(), b.industry(), b.coreOffer(), b.tone()),
                "logo", config.getRag().getTopK())
            : List.of();

        String userPrompt = assemblePrompt(brief, identity, precedent, previous, revision);

        // Claude occasionally emits structurally malformed JSON, and image generation can fail
        // transiently — retry the whole attempt rather than failing the request outright.
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Map<String, Object> concept = concept(agentId, config, userPrompt);
                return method == LogoOutput.Method.SVG_CONCEPT
                    ? buildSvgOutput(brief, concept, iteration)
                    : buildImageOutput(brief, method, identity, agentId, config, userPrompt, concept, iteration);
            } catch (IllegalStateException e) {
                lastFailure = e;
                log.warn("LogoAgent ({}) output failed on attempt {}/2: {}", method, attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private Map<String, Object> concept(String agentId, AgentProperties.AgentConfig config, String userPrompt) {
        String raw = chatClient.prompt()
            .system(agentProperties.systemPrompt(agentId))
            .user(userPrompt)
            .options(AnthropicChatOptions.builder()
                .model(config.getModel())
                .maxTokens(config.getMaxTokens())
                .build())
            .call()
            .content();
        return parseConcept(raw);
    }

    // ── Image methods: candidates → selection → symbol ───────────────────────

    /** A drawn lockup, the concept it was drawn from, and where it is stored. */
    private record Candidate(byte[] bytes, String url, Map<String, Object> concept) {}

    private LogoOutput buildImageOutput(DirectionBrief brief, LogoOutput.Method method, LogoIdentity identity,
                                        String agentId, AgentProperties.AgentConfig config, String userPrompt,
                                        Map<String, Object> concept, int iteration) {
        Renderer renderer = renderer(method);
        String stem = "assets/logos/%s/%s-%d".formatted(
            brief.engagementId(), brief.direction().name().toLowerCase(), iteration);

        List<Candidate> pool = new ArrayList<>(draw(renderer, concept, stem + "-r1", 1));
        int rounds = Math.max(0, extraRounds);
        LogoEvaluator.Selection selection = evaluator.select(
            brief, identity, summary(concept), bytes(pool), rounds > 0);

        for (int round = 2; selection.revise() && round <= rounds + 1; round++) {
            log.info("Logo round {} for {} direction of engagement {}: {}",
                round, brief.direction(), brief.engagementId(), selection.rationale());
            Map<String, Object> revised = concept(agentId, config,
                userPrompt + selectionFeedback(concept, selection));
            pool.addAll(draw(renderer, revised, stem + "-r" + round, pool.size() + 1));
            selection = evaluator.select(brief, identity, summary(revised), bytes(pool), round <= rounds);
            concept = revised;
        }

        Candidate chosen = pool.get(selection.index());
        Map<String, Object> chosenConcept = chosen.concept();
        String symbolUrl = cutSymbol(renderer, chosen, chosenConcept, stem);

        return new LogoOutput(
            brief.engagementId(),
            method,
            str(chosenConcept, "conceptDescription"),
            str(chosenConcept, "symbolism"),
            str(chosenConcept, "imagePrompt"),
            chosen.url(),
            null,
            str(chosenConcept, "reasoning"),
            iteration,
            symbolUrl,
            str(chosenConcept, "symbolDescription"),
            pool.stream().map(Candidate::url).toList(),
            selection.rationale()
        );
    }

    private List<Candidate> draw(Renderer renderer, Map<String, Object> concept, String stem, int firstNumber) {
        String prompt = str(concept, "imagePrompt");
        if (prompt == null || prompt.isBlank()) throw new IllegalStateException("Logo concept has no imagePrompt");
        List<byte[]> images = renderer.generate(prompt, Math.max(1, candidatesPerRound));
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            String path = stem + "-c" + (firstNumber + i) + ".png";
            store(path, images.get(i));
            out.add(new Candidate(images.get(i), ASSET_URL_PREFIX + path, concept));
        }
        return out;
    }

    /**
     * The chosen lockup's symbol alone. It is cut from the lockup's pixels when the layout
     * separates cleanly ({@link SymbolCrop}), so it is exactly the lockup's symbol; otherwise the
     * image model edits the wordmark out, which can redraw details. Failing here never fails the
     * logo: the lockup still stands.
     */
    private String cutSymbol(Renderer renderer, Candidate chosen, Map<String, Object> concept, String stem) {
        String path = stem + "-symbol.png";
        var cropped = SymbolCrop.crop(chosen.bytes());
        if (cropped.isPresent()) {
            try {
                store(path, cropped.get());
                return ASSET_URL_PREFIX + path;
            } catch (RuntimeException e) {
                log.warn("Storing the cropped logo symbol failed ({}): {}", stem, e.getMessage());
            }
        }
        log.info("Logo symbol for {} does not separate cleanly from the wordmark; editing it out", stem);
        String symbol = str(concept, "symbolDescription");
        String prompt = "This image is a finished logo lockup: a symbol and a wordmark. "
            + "Return the symbol alone. Remove the wordmark and every letter, word and tagline completely. "
            + "Keep the symbol exactly as it is drawn: the same shapes, proportions, colours and line weights; "
            + "do not redraw, restyle, simplify or add anything. "
            + (symbol == null || symbol.isBlank() ? "" : "The symbol is: " + symbol + ". ")
            + "Centre it on a square canvas with an even margin of about one sixth of the canvas on every side, "
            + "on a fully transparent background. No text, no frame, no shadow, no mockup.";
        try {
            byte[] bytes = renderer.edit(chosen.bytes(), prompt);
            store(path, bytes);
            return ASSET_URL_PREFIX + path;
        } catch (RuntimeException e) {
            log.warn("Logo symbol cut failed ({}); the lockup stands on its own: {}", stem, e.getMessage());
            return null;
        }
    }

    private static String selectionFeedback(Map<String, Object> concept, LogoEvaluator.Selection selection) {
        StringBuilder sb = new StringBuilder("## Creative Director's review of your first lockups\n");
        sb.append("None of the lockups drawn from your concept is ready for the client. Write a new ")
            .append("concept and prompt that fixes this; keep what the review says is working.\n\n");
        if (selection.rationale() != null) sb.append("Review: ").append(selection.rationale()).append("\n");
        AgentRevision r = selection.revision();
        if (r != null) {
            if (r.feedback() != null) sb.append("Feedback: ").append(r.feedback()).append("\n");
            if (r.specificIssues() != null) r.specificIssues().forEach(i -> sb.append("- ").append(i).append("\n"));
        }
        sb.append("\nThe prompt that produced them:\n").append(str(concept, "imagePrompt")).append("\n\n");
        return sb.toString();
    }

    private static String summary(Map<String, Object> concept) {
        StringBuilder sb = new StringBuilder();
        append(sb, "Concept", str(concept, "conceptDescription"));
        append(sb, "Symbol", str(concept, "symbolDescription"));
        append(sb, "Wordmark", str(concept, "wordmark"));
        append(sb, "Symbolism", str(concept, "symbolism"));
        append(sb, "Image prompt", str(concept, "imagePrompt"));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append(label).append(": ").append(value).append("\n");
    }

    private static List<byte[]> bytes(List<Candidate> pool) {
        return pool.stream().map(Candidate::bytes).toList();
    }

    // ── Image backends ────────────────────────────────────────────────────────

    /** The calls the logo flow needs from an image model. Usage is recorded here. */
    private interface Renderer {
        List<byte[]> generate(String prompt, int n);
        byte[] edit(byte[] source, String prompt);
    }

    private Renderer renderer(LogoOutput.Method method) {
        if (method == LogoOutput.Method.IDEOGRAM) {
            return new Renderer() {
                public List<byte[]> generate(String prompt, int n) {
                    List<byte[]> images = ideogramImages.generate(prompt, n);
                    usageService.recordImage(ideogramImages.model(), images.size());
                    return images;
                }
                public byte[] edit(byte[] source, String prompt) {
                    byte[] image = ideogramImages.edit(source, prompt).get(0);
                    usageService.recordImage(ideogramImages.model(), 1);
                    return image;
                }
            };
        }
        return new Renderer() {
            public List<byte[]> generate(String prompt, int n) {
                List<byte[]> images = openAiImages.generate(OpenAiImageClient.Request.logo(prompt, n));
                usageService.recordImage(openAiImages.model(), images.size());
                return images;
            }
            public byte[] edit(byte[] source, String prompt) {
                byte[] image = openAiImages.edit(source, OpenAiImageClient.Request.logo(prompt, 1)).get(0);
                usageService.recordImage(openAiImages.model(), 1);
                return image;
            }
        };
    }

    private void store(String blobPath, byte[] bytes) {
        try {
            blobStorageService.upload(blobPath, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store generated logo image: " + e.getMessage(), e);
        }
    }

    // ── SVG method ────────────────────────────────────────────────────────────

    private LogoOutput buildSvgOutput(DirectionBrief brief, Map<String, Object> concept, int iteration) {
        String svgMarkup = str(concept, "svgMarkup");
        SvgValidator.validate(svgMarkup);
        return new LogoOutput(
            brief.engagementId(),
            LogoOutput.Method.SVG_CONCEPT,
            str(concept, "conceptDescription"),
            str(concept, "symbolism"),
            null,
            null,
            svgMarkup,
            str(concept, "reasoning"),
            iteration
        );
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private String assemblePrompt(DirectionBrief brief, LogoIdentity identity, List<String> precedent,
                                  LogoOutput previous, AgentRevision revision) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Creative Direction: ").append(brief.direction().name()).append("\n\n");

        DirectionBrief.BrandContext b = brief.brand();
        sb.append("## Brand Context\n");
        sb.append("Name: ").append(b.name()).append("\n");
        sb.append("Industry: ").append(b.industry()).append("\n");
        sb.append("Target audience: ").append(b.targetAudience()).append("\n");
        sb.append("Core offer: ").append(b.coreOffer()).append("\n");
        sb.append("Differentiator: ").append(b.differentiator()).append("\n");
        sb.append("Personality: ").append(String.join(", ", b.personality())).append("\n");
        sb.append("Tone: ").append(b.tone()).append("\n\n");

        if (identity != null) {
            String rendered = identity.render();
            if (!rendered.isBlank()) {
                sb.append("## Visual Identity (chosen for this same direction — the logo must belong to it)\n")
                    .append(rendered).append("\n");
            }
        }

        if (!precedent.isEmpty()) {
            sb.append("## Knowledge & Precedent\n");
            precedent.forEach(p -> sb.append(p).append("\n\n"));
        }

        if (!brief.trainingInstructions().isEmpty()) {
            sb.append("## Specific Instructions\n");
            brief.trainingInstructions().forEach(i -> sb.append("- ").append(i).append("\n"));
            sb.append("\n");
        }

        if (brief.additionalContext() != null && !brief.additionalContext().isBlank()) {
            sb.append("## Additional Context\n").append(brief.additionalContext()).append("\n\n");
        }

        if (previous != null && revision != null) {
            sb.append("## Revision Request\n");
            sb.append("Creative Director feedback: ").append(revision.feedback()).append("\n");
            if (!revision.specificIssues().isEmpty()) {
                sb.append("Issues to address:\n");
                revision.specificIssues().forEach(i -> sb.append("- ").append(i).append("\n"));
            }
            if (!revision.fieldsToRevise().isEmpty()) {
                sb.append("Fields to revise: ").append(String.join(", ", revision.fieldsToRevise())).append("\n");
            }
            sb.append("\nPrevious concept for reference:\n");
            sb.append("Concept: ").append(previous.conceptDescription()).append("\n");
            sb.append("Symbolism: ").append(previous.symbolism()).append("\n");
            if (previous.imagePrompt() != null) {
                sb.append("Image prompt: ").append(previous.imagePrompt()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConcept(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            return objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse LogoAgent output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
