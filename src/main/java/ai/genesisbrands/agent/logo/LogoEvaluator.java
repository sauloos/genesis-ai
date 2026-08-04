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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Genesis AI's creative-director review of LogoAgent output. For SVG_CONCEPT output
 * this is a text-only rubric review, like CopyEvaluator/VisualIdentityEvaluator. For
 * DALLE output, Genesis AI looks directly at the generated image (Claude vision) rather
 * than only the written concept description.
 */
@Service
public class LogoEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LogoEvaluator.class);
    private static final String AGENT_ID_DALLE = "logo-dalle-evaluator";
    private static final String AGENT_ID_SVG = "logo-svg-evaluator";
    private static final String RUBRIC_KEY = "evaluation/logo-rubric.yaml";
    private static final String ASSET_URL_PREFIX = "/api/assets/";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final Layer1Service layer1Service;
    private final ObjectMapper objectMapper;
    private final BlobStorageService blobStorageService;

    private final String systemPromptDalle;
    private final String systemPromptSvg;

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
        this.systemPromptDalle = loadPrompt(AGENT_ID_DALLE);
        this.systemPromptSvg = loadPrompt(AGENT_ID_SVG);
    }

    public EvaluationResult evaluate(DirectionBrief brief, LogoOutput output) {
        boolean isDalle = output.method() == LogoOutput.Method.DALLE;
        AgentProperties.AgentConfig config = agentProperties.get(isDalle ? AGENT_ID_DALLE : AGENT_ID_SVG);
        String systemPrompt = isDalle ? systemPromptDalle : systemPromptSvg;
        String userPromptText = assemblePrompt(brief, output);

        byte[] imageBytes = isDalle ? fetchImageBytes(output.imageUrl()) : null;

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

    private String assemblePrompt(DirectionBrief brief, LogoOutput output) {
        StringBuilder sb = new StringBuilder();

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

        sb.append("## Logo Agent Output (method: ").append(output.method()).append(")\n");
        sb.append("Concept description: ").append(output.conceptDescription()).append("\n\n");
        sb.append("Symbolism: ").append(output.symbolism()).append("\n\n");

        if (output.method() == LogoOutput.Method.DALLE) {
            sb.append("Image prompt used: ").append(output.imagePrompt()).append("\n\n");
            sb.append("The generated image is attached below.\n\n");
        } else {
            sb.append("SVG markup:\n").append(output.svgMarkup()).append("\n\n");
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private EvaluationResult parse(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            Map<String, Object> map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);

            EvaluationResult.Verdict verdict = EvaluationResult.Verdict.valueOf((String) map.get("verdict"));
            String summary = (String) map.get("summary");

            AgentRevision revision = null;
            Map<String, Object> revMap = (Map<String, Object>) map.get("revision");
            if (revMap != null) {
                revision = new AgentRevision(
                    (String) revMap.get("feedback"),
                    (List<String>) revMap.getOrDefault("specificIssues", List.of()),
                    (List<String>) revMap.getOrDefault("fieldsToRevise", List.of())
                );
            }

            return new EvaluationResult(verdict, summary, revision);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse LogoEvaluator output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }

    private String loadPrompt(String agentId) {
        AgentProperties.AgentConfig config = agentProperties.get(agentId);
        try {
            return new ClassPathResource(config.getSystemPrompt())
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load system prompt: " + config.getSystemPrompt(), e);
        }
    }
}
