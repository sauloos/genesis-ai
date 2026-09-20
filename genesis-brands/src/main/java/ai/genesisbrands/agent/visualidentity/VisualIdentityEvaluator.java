package ai.genesisbrands.agent.visualidentity;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.core.EvaluationResult;
import ai.genesisbrands.service.Layer1Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Genesis AI's creative-director review of VisualIdentityAgent output — scores a draft
 * against the visual identity evaluation rubric
 * (knowledge/layer1/modules/evaluation/visual-identity-rubric.yaml) and returns either
 * ACCEPT or REVISE with structured feedback.
 */
@Service
public class VisualIdentityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(VisualIdentityEvaluator.class);
    private static final String AGENT_ID = "visual-identity-evaluator";
    private static final String RUBRIC_KEY = "evaluation/visual-identity-rubric.yaml";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final Layer1Service layer1Service;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public VisualIdentityEvaluator(ChatClient.Builder chatClientBuilder,
                                    AgentProperties agentProperties,
                                    Layer1Service layer1Service,
                                    ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.layer1Service = layer1Service;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadSystemPrompt();
    }

    public EvaluationResult evaluate(DirectionBrief brief, VisualIdentityOutput output) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        String userPrompt = assemblePrompt(brief, output);

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
                return parse(raw);
            } catch (IllegalStateException e) {
                lastFailure = e;
                log.warn("VisualIdentityEvaluator output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private String assemblePrompt(DirectionBrief brief, VisualIdentityOutput output) {
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

        sb.append("## Visual Identity Agent Output\n");
        sb.append("Colour palette:\n");
        output.colorPalette().forEach(s ->
            sb.append("- ").append(s.name()).append(" (").append(s.hex()).append(", ").append(s.role())
                .append(") — ").append(s.rationale()).append("\n"));
        sb.append("\n");
        sb.append("Typography: headline = ").append(output.typography().headlineFont())
            .append(", body = ").append(output.typography().bodyFont()).append("\n");
        sb.append("Pairing rationale: ").append(output.typography().pairingRationale()).append("\n\n");
        sb.append("Mood direction: ").append(output.moodDirection()).append("\n\n");

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
            throw new IllegalStateException("Failed to parse VisualIdentityEvaluator output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }

    private String loadSystemPrompt() {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        try {
            return new ClassPathResource(config.getSystemPrompt())
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load system prompt: " + config.getSystemPrompt(), e);
        }
    }
}
