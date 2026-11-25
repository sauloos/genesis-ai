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
 * <p>For the image methods (DALLE = the OpenAI/Azure gpt-image model, IDEOGRAM) it works the way a
 * studio does: {@link LogoConceptStudio} writes a sketchbook of concepts in words and the creative
 * director pins up the strongest few; for each, the agent writes an art-directed prompt for the
 * primary logo (a lockup, or a wordmark carrying its own device, in the direction's own typeface
 * and palette); the image model draws several candidates of each on a transparent background;
 * Genesis AI looks at all of them and picks one, or sends the work back once for new concepts if
 * none is good enough; and the icon is then cut from the chosen logo, so the two always match.
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
    private final LogoConceptStudio studio;
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
                     LogoConceptStudio studio,
                     AgentProperties agentProperties,
                     ObjectMapper objectMapper,
                     BlobStorageService blobStorageService,
                     RetrievalService retrievalService,
                     UsageService usageService) {
        this.chatClient = chatClientBuilder.build();
        this.openAiImages = openAiImages;
        this.ideogramImages = ideogramImages;
        this.evaluator = evaluator;
        this.studio = studio;
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
                return method == LogoOutput.Method.SVG_CONCEPT
                    ? buildSvgOutput(brief, concept(agentId, config, userPrompt), iteration)
                    : buildImageOutput(brief, method, identity, agentId, config, userPrompt, iteration);
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

    // ── Image methods: concepts → candidates → selection → icon ─────────────

    /** A drawn logo, the art direction it was drawn from, its concept, and where it is stored. */
    private record Candidate(byte[] bytes, String url, Map<String, Object> artDirection,
                             LogoConceptStudio.Concept concept) {}

    private LogoOutput buildImageOutput(DirectionBrief brief, LogoOutput.Method method, LogoIdentity identity,
                                        String agentId, AgentProperties.AgentConfig config, String userPrompt,
                                        int iteration) {
        Renderer renderer = renderer(method);
        String stem = "assets/logos/%s/%s-%d".formatted(
            brief.engagementId(), brief.direction().name().toLowerCase(), iteration);

        List<Candidate> pool = new ArrayList<>();
        StringBuilder summary = new StringBuilder(studio.takenByOtherDirections(brief));
        List<LogoConceptStudio.Concept> concepts = studio.develop(brief, identity, userPrompt, null);
        drawConcepts(renderer, concepts, agentId, config, userPrompt, stem + "-r1", pool, summary);

        int rounds = Math.max(0, extraRounds);
        LogoEvaluator.Selection selection = evaluator.select(
            brief, identity, summary.toString(), bytes(pool), rounds > 0);

        for (int round = 2; selection.revise() && round <= rounds + 1; round++) {
            log.info("Logo round {} for {} direction of engagement {}: {}",
                round, brief.direction(), brief.engagementId(), selection.rationale());
            String review = selectionFeedback(summary.toString(), selection);
            concepts = studio.develop(brief, identity, userPrompt, review);
            drawConcepts(renderer, concepts, agentId, config, userPrompt, stem + "-r" + round, pool, summary);
            selection = evaluator.select(brief, identity, summary.toString(), bytes(pool), round <= rounds);
        }

        Candidate chosen = pool.get(selection.index());
        Map<String, Object> art = chosen.artDirection();
        studio.recordWinner(brief, chosen.concept(), str(art, "conceptDescription"));
        String symbolUrl = cutSymbol(renderer, chosen, stem);

        return new LogoOutput(
            brief.engagementId(),
            method,
            str(art, "conceptDescription"),
            str(art, "symbolism"),
            str(art, "imagePrompt"),
            chosen.url(),
            null,
            str(art, "reasoning"),
            iteration,
            symbolUrl,
            str(art, "symbolDescription"),
            pool.stream().map(Candidate::url).toList(),
            selection.rationale()
        );
    }

    /**
     * Art-directs and draws each chosen concept, adding the candidates to the pool and a line per
     * concept to the selector's summary. One concept failing does not lose the others' drawings;
     * only a round that draws nothing fails.
     */
    private void drawConcepts(Renderer renderer, List<LogoConceptStudio.Concept> concepts, String agentId,
                              AgentProperties.AgentConfig config, String userPrompt, String stem,
                              List<Candidate> pool, StringBuilder summary) {
        int before = pool.size();
        RuntimeException lastFailure = null;
        for (int i = 0; i < concepts.size(); i++) {
            LogoConceptStudio.Concept c = concepts.get(i);
            try {
                Map<String, Object> art = concept(agentId, config, userPrompt
                    + "## The chosen concept to draw\n" + c.render() + "\n");
                int first = pool.size() + 1;
                List<Candidate> drawn = draw(renderer, art, c, stem + "-k" + (i + 1), first);
                pool.addAll(drawn);
                summary.append("### Candidates ").append(first).append("–").append(pool.size())
                    .append(": concept \"").append(c.name()).append("\" (")
                    .append(c.technique()).append(", ").append(c.wordmark() ? "WORDMARK" : "LOCKUP").append(")\n")
                    .append(c.render()).append(summary(art)).append("\n");
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Logo concept \"{}\" could not be drawn ({}): {}", c.name(), stem, e.getMessage());
            }
        }
        if (pool.size() == before) {
            throw new IllegalStateException("No logo concept could be drawn"
                + (lastFailure == null ? "" : ": " + lastFailure.getMessage()), lastFailure);
        }
    }

    private List<Candidate> draw(Renderer renderer, Map<String, Object> art, LogoConceptStudio.Concept concept,
                                 String stem, int firstNumber) {
        String prompt = str(art, "imagePrompt");
        if (prompt == null || prompt.isBlank()) throw new IllegalStateException("Logo concept has no imagePrompt");
        List<byte[]> images = renderer.generate(prompt, Math.max(1, candidatesPerRound));
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            String path = stem + "-c" + (firstNumber + i) + ".png";
            store(path, images.get(i));
            out.add(new Candidate(images.get(i), ASSET_URL_PREFIX + path, art, concept));
        }
        return out;
    }

    /**
     * The chosen logo's icon alone. For a lockup it is cut from the lockup's pixels when the
     * layout separates cleanly ({@link SymbolCrop}), so it is exactly the lockup's symbol;
     * otherwise, and always for a wordmark (whose icon is a letter or device inside the type), the
     * image model edits everything else out, which can redraw details. Failing here never fails
     * the logo: the logo still stands.
     */
    private String cutSymbol(Renderer renderer, Candidate chosen, String stem) {
        String path = stem + "-symbol.png";
        boolean wordmark = chosen.concept() != null && chosen.concept().wordmark();
        if (!wordmark) {
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
        }
        String symbol = str(chosen.artDirection(), "symbolDescription");
        String described = symbol == null || symbol.isBlank() ? "" : "The icon is: " + symbol + ". ";
        String prompt = (wordmark
            ? "This image is a finished wordmark logo. Return only the element that carries its idea, as a "
                + "stand-alone app icon: " + described + "Remove every other letter and word completely. "
                + "Keep that element exactly as it is drawn: the same shapes, proportions, colours and weights; "
                + "do not redraw, restyle, simplify or add anything. "
            : "This image is a finished logo lockup: a symbol and a wordmark. "
                + "Return the symbol alone. Remove the wordmark and every letter, word and tagline completely. "
                + "Keep the symbol exactly as it is drawn: the same shapes, proportions, colours and line weights; "
                + "do not redraw, restyle, simplify or add anything. " + described)
            + "Centre it on a square canvas with an even margin of about one sixth of the canvas on every side, "
            + "on a fully transparent background. No text, no frame, no shadow, no mockup.";
        try {
            byte[] bytes = renderer.edit(chosen.bytes(), prompt);
            store(path, bytes);
            return ASSET_URL_PREFIX + path;
        } catch (RuntimeException e) {
            log.warn("Logo icon cut failed ({}); the logo stands on its own: {}", stem, e.getMessage());
            return null;
        }
    }

    private static String selectionFeedback(String summary, LogoEvaluator.Selection selection) {
        StringBuilder sb = new StringBuilder("## Creative Director's review of the drawn logos\n");
        sb.append("None of the logos drawn from the last concepts is ready for the client. Write a new set ")
            .append("of concepts that answers this review; keep what the review says is working.\n\n");
        if (selection.rationale() != null) sb.append("Review: ").append(selection.rationale()).append("\n");
        AgentRevision r = selection.revision();
        if (r != null) {
            if (r.feedback() != null) sb.append("Feedback: ").append(r.feedback()).append("\n");
            if (r.specificIssues() != null) r.specificIssues().forEach(i -> sb.append("- ").append(i).append("\n"));
        }
        sb.append("\nWhat was drawn:\n").append(summary).append("\n");
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
