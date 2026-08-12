package ai.genesisbrands.agent.logo;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class LogoAgent {

    private static final Logger log = LoggerFactory.getLogger(LogoAgent.class);
    private static final String AGENT_ID_DALLE = "logo-dalle";
    private static final String AGENT_ID_SVG = "logo-svg";
    // OpenAI retired dall-e-3; gpt-image-1 is the current image model. It always returns
    // b64_json (no response_format param) and takes "size" as a string, not width/height.
    private static final String IMAGE_MODEL = "gpt-image-1";

    private final ChatClient chatClient;
    private final ImageModel imageModel;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final BlobStorageService blobStorageService;
    private final RetrievalService retrievalService;

    private final String systemPromptDalle;
    private final String systemPromptSvg;

    public LogoAgent(ChatClient.Builder chatClientBuilder,
                      ImageModel imageModel,
                      AgentProperties agentProperties,
                      ObjectMapper objectMapper,
                      BlobStorageService blobStorageService,
                      RetrievalService retrievalService) {
        this.chatClient = chatClientBuilder.build();
        this.imageModel = imageModel;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.blobStorageService = blobStorageService;
        this.retrievalService = retrievalService;
        this.systemPromptDalle = loadPrompt(AGENT_ID_DALLE);
        this.systemPromptSvg = loadPrompt(AGENT_ID_SVG);
    }

    // ── Public interface (defines the SpecialistAgent contract) ───────────────

    public LogoOutput execute(DirectionBrief brief, LogoOutput.Method method) {
        return run(brief, method, null, null, 1);
    }

    public LogoOutput executeWithRevision(DirectionBrief brief, LogoOutput previous, AgentRevision revision) {
        return run(brief, previous.method(), previous, revision, previous.iteration() + 1);
    }

    private LogoOutput run(DirectionBrief brief, LogoOutput.Method method,
                            LogoOutput previous, AgentRevision revision, int iteration) {
        String agentId = method == LogoOutput.Method.DALLE ? AGENT_ID_DALLE : AGENT_ID_SVG;
        AgentProperties.AgentConfig config = agentProperties.get(agentId);
        String systemPrompt = method == LogoOutput.Method.DALLE ? systemPromptDalle : systemPromptSvg;

        DirectionBrief.BrandContext b = brief.brand();
        List<String> precedent = config.getRag().isEnabled()
            ? retrievalService.retrieveForAgent(
                String.join(" ", b.name(), b.industry(), b.coreOffer(), b.tone()),
                "logo", config.getRag().getTopK())
            : List.of();

        String userPrompt = assemblePrompt(brief, precedent, previous, revision);

        // Claude occasionally emits structurally malformed JSON, and DALL-E image
        // generation can fail transiently — retry the whole attempt (fresh concept
        // and, for DALLE, a fresh image) rather than failing the request outright.
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(AnthropicChatOptions.builder()
                    .model(config.getModel())
                    .maxTokens(config.getMaxTokens())
                    .build())
                .call()
                .content();
            try {
                Map<String, Object> concept = parseConcept(raw);
                return method == LogoOutput.Method.DALLE
                    ? buildDalleOutput(brief, concept, iteration)
                    : buildSvgOutput(brief, concept, iteration);
            } catch (IllegalStateException e) {
                lastFailure = e;
                log.warn("LogoAgent ({}) output failed on attempt {}/2: {}", method, attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private LogoOutput buildDalleOutput(DirectionBrief brief, Map<String, Object> concept, int iteration) {
        String imagePrompt = (String) concept.get("imagePrompt");
        byte[] imageBytes = generateImage(imagePrompt);

        String blobPath = "assets/logos/%s/%s-%d.png".formatted(
            brief.engagementId(), brief.direction().name().toLowerCase(), iteration);
        try {
            blobStorageService.upload(blobPath, imageBytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store generated logo image: " + e.getMessage(), e);
        }

        return new LogoOutput(
            brief.engagementId(),
            LogoOutput.Method.DALLE,
            (String) concept.get("conceptDescription"),
            (String) concept.get("symbolism"),
            imagePrompt,
            "/api/assets/" + blobPath,
            null,
            (String) concept.get("reasoning"),
            iteration
        );
    }

    private LogoOutput buildSvgOutput(DirectionBrief brief, Map<String, Object> concept, int iteration) {
        String svgMarkup = (String) concept.get("svgMarkup");
        SvgValidator.validate(svgMarkup);
        return new LogoOutput(
            brief.engagementId(),
            LogoOutput.Method.SVG_CONCEPT,
            (String) concept.get("conceptDescription"),
            (String) concept.get("symbolism"),
            null,
            null,
            svgMarkup,
            (String) concept.get("reasoning"),
            iteration
        );
    }

    private byte[] generateImage(String imagePrompt) {
        try {
            OpenAiImageOptions options = OpenAiImageOptions.builder()
                .model(IMAGE_MODEL)
                .quality("high")
                .N(1)
                .build();
            options.setSize("1024x1024");
            ImageResponse response = imageModel.call(new ImagePrompt(imagePrompt, options));
            String b64 = response.getResult().getOutput().getB64Json();
            return Base64.getDecoder().decode(b64);
        } catch (Exception e) {
            throw new IllegalStateException("DALL-E image generation failed: " + e.getMessage(), e);
        }
    }

    private String assemblePrompt(DirectionBrief brief, List<String> precedent,
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
            sb.append("Symbolism: ").append(previous.symbolism()).append("\n\n");
        }

        return sb.toString();
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
