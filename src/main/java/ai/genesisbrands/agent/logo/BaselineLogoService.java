package ai.genesisbrands.agent.logo;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.service.BlobStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * Baseline comparison for the A/B playground: same underlying models as LogoAgent, but
 * no persona, no craft rubric, no revision loop — just the raw brand facts, the way a
 * generic request would use them. For DALLE, the image prompt is a naive template
 * rather than an art-directed prompt. Isolates the value the orchestration adds.
 */
@Service
public class BaselineLogoService {

    private static final Logger log = LoggerFactory.getLogger(BaselineLogoService.class);
    private static final String AGENT_ID_SVG = "logo-svg";
    // OpenAI retired dall-e-3; gpt-image-1 is the current image model. It always returns
    // b64_json (no response_format param) and takes "size" as a string, not width/height.
    private static final String IMAGE_MODEL = "gpt-image-1";

    private final ChatClient chatClient;
    private final ImageModel imageModel;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final BlobStorageService blobStorageService;

    public BaselineLogoService(ChatClient.Builder chatClientBuilder,
                                ImageModel imageModel,
                                AgentProperties agentProperties,
                                ObjectMapper objectMapper,
                                BlobStorageService blobStorageService) {
        this.chatClient = chatClientBuilder.build();
        this.imageModel = imageModel;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.blobStorageService = blobStorageService;
    }

    public BaselineLogoOutput generate(DirectionBrief brief, LogoOutput.Method method) {
        return method == LogoOutput.Method.DALLE ? generateDalle(brief) : generateSvg(brief);
    }

    private BaselineLogoOutput generateDalle(DirectionBrief brief) {
        DirectionBrief.BrandContext b = brief.brand();
        String imagePrompt = "A logo icon for a company called %s, a %s. %s. Style: %s."
            .formatted(b.name(), b.industry(), b.coreOffer(), b.tone());

        OpenAiImageOptions options = OpenAiImageOptions.builder()
            .model(IMAGE_MODEL)
            .quality("medium")
            .N(1)
            .build();
        options.setSize("1024x1024");
        ImageResponse response = imageModel.call(new ImagePrompt(imagePrompt, options));
        byte[] imageBytes = Base64.getDecoder().decode(response.getResult().getOutput().getB64Json());

        String blobPath = "assets/logos/%s/baseline-%s.png".formatted(
            brief.engagementId(), brief.direction().name().toLowerCase());
        try {
            blobStorageService.upload(blobPath, imageBytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store baseline logo image: " + e.getMessage(), e);
        }

        return new BaselineLogoOutput(LogoOutput.Method.DALLE, null, imagePrompt, "/api/assets/" + blobPath, null);
    }

    private BaselineLogoOutput generateSvg(DirectionBrief brief) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID_SVG);
        DirectionBrief.BrandContext b = brief.brand();

        String prompt = """
            Design a logo mark for this company:

            Name: %s
            Industry: %s
            Target audience: %s
            Core offer: %s

            Give me a short concept description and a simple SVG (viewBox around 200x200, \
            no text elements, no external references).

            Return only raw JSON, no markdown fences, with exactly these keys: \
            conceptDescription, svgMarkup.
            """.formatted(b.name(), b.industry(), b.targetAudience(), b.coreOffer());

        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = chatClient.prompt()
                .user(prompt)
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
                log.warn("BaselineLogoService (svg) output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    @SuppressWarnings("unchecked")
    private BaselineLogoOutput parse(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            Map<String, Object> map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);

            return new BaselineLogoOutput(
                LogoOutput.Method.SVG_CONCEPT,
                (String) map.get("conceptDescription"),
                null,
                null,
                (String) map.get("svgMarkup")
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse baseline logo output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
