package ai.genesisbrands.agent.logo;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.EvaluationResult;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.Layer1Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Genesis AI's creative-director review of LogoAgent output. For SVG_CONCEPT output
 * this is a text-only rubric review, like CopyEvaluator/VisualIdentityEvaluator. For
 * DALLE output, Genesis AI looks directly at the generated image (Claude vision) rather
 * than only the written concept description.
 *
 * <p>{@link #select} is the step before that review: Genesis AI looks at every candidate lockup
 * the Logo Agent drew and picks the one that goes forward (config key {@code logo-selector}).
 */
@Service
public class LogoEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LogoEvaluator.class);
    private static final String AGENT_ID_DALLE = "logo-dalle-evaluator";
    private static final String AGENT_ID_SVG = "logo-svg-evaluator";
    private static final String AGENT_ID_SELECTOR = "logo-selector";
    private static final String RUBRIC_KEY = "evaluation/logo-rubric.yaml";
    private static final String ASSET_URL_PREFIX = "/api/assets/";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final Layer1Service layer1Service;
    private final ObjectMapper objectMapper;
    private final BlobStorageService blobStorageService;

    public LogoEvaluator(ChatClient.Builder chatClientBuilder,
                          AgentProperties agentProperties,
                          Layer1Service layer1Service,
                          ObjectMapper objectMapper,
                          BlobStorageService blobStorageService) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.layer1Service = layer1Service;
        this.objectMapper = objectMapper;
        this.blobStorageService = blobStorageService;
    }

    public EvaluationResult evaluate(DirectionBrief brief, LogoOutput output) {
        return evaluate(brief, output, null);
    }

    public EvaluationResult evaluate(DirectionBrief brief, LogoOutput output, LogoIdentity identity) {
        // Both DALLE and IDEOGRAM produce raster images — evaluate via Claude Vision.
        // SVG_CONCEPT is text-only.
        boolean isImageBased = output.method() == LogoOutput.Method.DALLE
            || output.method() == LogoOutput.Method.IDEOGRAM;
        String configKey = isImageBased ? AGENT_ID_DALLE : AGENT_ID_SVG;
        AgentProperties.AgentConfig config = agentProperties.get(configKey);
        String systemPrompt = agentProperties.systemPrompt(configKey);
        String userPromptText = assemblePrompt(brief, output, identity);

        byte[] imageBytes = isImageBased ? fetchImageBytes(output.imageUrl()) : null;

        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> {
                    u.text(userPromptText);
                    if (imageBytes != null) {
                        u.media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes));
                    }
                })
                .options(AnthropicChatOptions.builder()
                    .model(config.getModel())
                    .maxTokens(config.getMaxTokens())
                    .build())
                .call()
                .content();
            try {
                return parse(raw);
            } catch (IllegalStateException e) {
                lastFailure = e;
                log.warn("LogoEvaluator output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private byte[] fetchImageBytes(String imageUrl) {
        String blobPath = imageUrl.startsWith(ASSET_URL_PREFIX)
            ? imageUrl.substring(ASSET_URL_PREFIX.length())
            : imageUrl;
        try {
            return blobStorageService.download(blobPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load generated logo image for evaluation: " + e.getMessage(), e);
        }
    }

    private String assemblePrompt(DirectionBrief brief, LogoOutput output, LogoIdentity identity) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, brief, identity);

        sb.append("## Logo Agent Output (method: ").append(output.method()).append(")\n");
        sb.append("Concept description: ").append(output.conceptDescription()).append("\n\n");
        sb.append("Symbolism: ").append(output.symbolism()).append("\n\n");

        if (output.method() == LogoOutput.Method.SVG_CONCEPT) {
            sb.append("SVG markup:\n").append(output.svgMarkup()).append("\n\n");
        } else {
            sb.append("Image prompt used: ").append(output.imagePrompt()).append("\n\n");
            sb.append("The generated image (the primary lockup) is attached below.\n\n");
        }

        return sb.toString();
    }

    /** The creative director's pick among logo candidates, and whether all of them miss. */
    public record Selection(int index, boolean revise, String rationale, AgentRevision revision) {
        static Selection fallback(String why) {
            return new Selection(0, false, why, null);
        }
    }

    /**
     * Looks at every candidate lockup at once and picks the strongest. With {@code allowRevise},
     * it may also say none is good enough and explain what the next round should change; the
     * chosen index is still the best of what exists. A failed review never fails the logo: it
     * falls back to the first candidate.
     */
    public Selection select(DirectionBrief brief, LogoIdentity identity, String conceptSummary,
                            List<byte[]> candidates, boolean allowRevise) {
        if (candidates.size() < 2 && !allowRevise) {
            return Selection.fallback("Only one candidate.");
        }
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID_SELECTOR);
        String systemPrompt = agentProperties.systemPrompt(AGENT_ID_SELECTOR);

        StringBuilder sb = new StringBuilder();
        appendContext(sb, brief, identity);
        sb.append("## The concepts and how they were drawn\n").append(conceptSummary).append("\n\n");
        sb.append("## Candidates\n").append(candidates.size())
            .append(" candidate logos are attached, in order: candidate 1 is the first image.\n");
        sb.append(allowRevise
            ? "You may return REVISE if no candidate is good enough to show the client.\n"
            : "This is the final round: return ACCEPT with the strongest candidate.\n");
        String userText = sb.toString();

        try {
            String raw = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> {
                    u.text(userText);
                    candidates.forEach(c -> u.media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(c)));
                })
                .options(AnthropicChatOptions.builder()
                    .model(config.getModel())
                    .maxTokens(config.getMaxTokens())
                    .build())
                .call()
                .content();
            Map<String, Object> map = readJson(raw);
            int choice = map.get("choice") instanceof Number n ? n.intValue() : 1;
            int index = Math.max(0, Math.min(candidates.size() - 1, choice - 1));
            boolean revise = allowRevise && "REVISE".equalsIgnoreCase(String.valueOf(map.get("verdict")));
            return new Selection(index, revise, (String) map.get("rationale"), revision(map));
        } catch (Exception e) {
            log.warn("Logo selection failed for engagement {}; using the first candidate: {}",
                brief.engagementId(), e.getMessage());
            return Selection.fallback("Selection unavailable: " + e.getMessage());
        }
    }

    private void appendContext(StringBuilder sb, DirectionBrief brief, LogoIdentity identity) {
        sb.append("## Evaluation Rubric\n\n").append(layer1Service.getModule(RUBRIC_KEY)).append("\n\n");

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
            if (!rendered.isBlank()) sb.append("## Visual Identity (same direction)\n").append(rendered).append("\n");
        }
        if (brief.additionalContext() != null && !brief.additionalContext().isBlank()) {
            sb.append("## Additional Context\n").append(brief.additionalContext()).append("\n\n");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String raw) throws IOException {
        String json = raw.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }
        return objectMapper.reader()
            .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .with(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static AgentRevision revision(Map<String, Object> map) {
        Map<String, Object> revMap = (Map<String, Object>) map.get("revision");
        if (revMap == null) return null;
        return new AgentRevision(
            (String) revMap.get("feedback"),
            (List<String>) revMap.getOrDefault("specificIssues", List.of()),
            (List<String>) revMap.getOrDefault("fieldsToRevise", List.of())
        );
    }

    private EvaluationResult parse(String raw) {
        try {
            Map<String, Object> map = readJson(raw);
            EvaluationResult.Verdict verdict = EvaluationResult.Verdict.valueOf((String) map.get("verdict"));
            return new EvaluationResult(verdict, (String) map.get("summary"), revision(map));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse LogoEvaluator output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
