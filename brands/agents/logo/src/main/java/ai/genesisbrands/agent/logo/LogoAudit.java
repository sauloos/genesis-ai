package ai.genesisbrands.agent.logo;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.service.Layer1Service;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.util.Map;
import java.util.Set;

/**
 * Assesses the logo a client already has (config key {@code logo-audit}, Layer 1
 * {@code evaluation/existing-logo-audit.yaml}): the creative director looks at the image against
 * what the client told us in discovery and how they feel about it, scores it, and recommends
 * keeping, evolving or replacing it. One vision call; no image is drawn.
 */
@Service
public class LogoAudit {

    private static final Logger log = LoggerFactory.getLogger(LogoAudit.class);
    static final String AGENT_ID = "logo-audit";
    static final String AUDIT_KEY = "evaluation/existing-logo-audit.yaml";
    private static final String RUBRIC_KEY = "evaluation/logo-rubric.yaml";
    private static final Set<String> VIEWABLE = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final Layer1Service layer1Service;
    private final ObjectMapper objectMapper;

    public LogoAudit(ChatClient.Builder chatClientBuilder, AgentProperties agentProperties,
                     Layer1Service layer1Service, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.layer1Service = layer1Service;
        this.objectMapper = objectMapper;
    }

    /** True when the model can look at this file type; an SVG or PDF must be rasterised first. */
    public static boolean canView(String contentType) {
        return contentType != null && VIEWABLE.contains(contentType.toLowerCase());
    }

    /**
     * @param image        the logo file
     * @param contentType  its type, one {@link #canView} accepts
     * @param brandContext what the client told us in discovery, as plain text
     * @param clientNote   how the client feels about their logo, or null
     * @param clientIntent their answer to keep / evolve / start fresh, or null
     */
    public LogoAssessment assess(byte[] image, String contentType, String brandContext,
                                 String clientNote, String clientIntent) {
        if (!canView(contentType)) {
            throw new IllegalArgumentException("Logo files of type " + contentType + " cannot be assessed yet");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Existing logo audit (Layer 1 module — authoritative)\n").append(module(AUDIT_KEY)).append("\n\n");
        sb.append("## Logo rubric (reference)\n").append(module(RUBRIC_KEY)).append("\n\n");
        sb.append("## Logo craft (reference)\n").append(module(LogoConceptStudio.CRAFT_KEY)).append("\n\n");
        sb.append("## What the client told us in discovery\n").append(brandContext == null ? "(nothing)" : brandContext).append("\n\n");
        sb.append("## How the client feels about their logo\n")
            .append(clientNote == null || clientNote.isBlank() ? "They did not say." : clientNote.strip()).append("\n\n");
        if (clientIntent != null && !clientIntent.isBlank()) {
            sb.append("## What they think they want to do with it\n").append(clientIntent.strip()).append("\n\n");
        }
        sb.append("## Task\nThe logo is attached. Assess it.\n");
        String user = sb.toString();

        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = chatClient.prompt()
                    .system(agentProperties.systemPrompt(AGENT_ID))
                    .user(u -> u.text(user).media(MimeType.valueOf(contentType), new ByteArrayResource(image)))
                    .options(AnthropicChatOptions.builder()
                        .model(config.getModel())
                        .maxTokens(config.getMaxTokens())
                        .build())
                    .call()
                    .content();
                LogoAssessment a = LogoAssessment.from(readJson(raw));
                log.info("Logo audit: {} at {}/100 ({} confidence)", a.verdict(), a.score(), a.confidence());
                return a;
            } catch (IllegalStateException e) {
                last = e;
                log.warn("Logo audit attempt {}/2 failed: {}", attempt, e.getMessage());
            }
        }
        throw last;
    }

    private String module(String key) {
        try {
            return layer1Service.getModule(key);
        } catch (IllegalStateException e) {
            log.warn("Layer 1 module {} is missing; the logo audit runs without it", key);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String raw) {
        if (raw == null) throw new IllegalStateException("The logo audit returned nothing");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("The logo audit returned no JSON");
        try {
            return objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .with(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .readValue(raw.substring(start, end + 1), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("The logo audit's JSON did not parse: " + e.getMessage(), e);
        }
    }
}
