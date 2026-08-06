package ai.genesisbrands.agent.playbook;

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
 * Genesis AI's creative-director review of PlaybookAgent output — scores a draft
 * against the playbook evaluation rubric
 * (knowledge/layer1/modules/evaluation/playbook-rubric.yaml) and returns either
 * ACCEPT or REVISE with structured feedback.
 */
@Service
public class PlaybookEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PlaybookEvaluator.class);
    private static final String AGENT_ID = "playbook-evaluator";
    private static final String RUBRIC_KEY = "evaluation/playbook-rubric.yaml";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final Layer1Service layer1Service;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public PlaybookEvaluator(ChatClient.Builder chatClientBuilder,
                              AgentProperties agentProperties,
                              Layer1Service layer1Service,
                              ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.layer1Service = layer1Service;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadSystemPrompt();
    }

    public EvaluationResult evaluate(PlaybookInput input, PlaybookOutput output) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        String userPrompt = assemblePrompt(input, output);

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
                log.warn("PlaybookEvaluator output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private String assemblePrompt(PlaybookInput input, PlaybookOutput output) {
        DirectionBrief brief = input.brief();
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

        sb.append("## Source Specialist Outputs — the Playbook must be grounded in these\n\n");
        sb.append("### Copy Agent output\n").append(toJson(input.copy())).append("\n\n");
        sb.append("### Visual Identity Agent output\n").append(toJson(input.visualIdentity())).append("\n\n");
        sb.append("### Logo Agent output\n").append(toJson(input.logo())).append("\n\n");

        sb.append("## Playbook Assembly Agent Output\n");
        sb.append("Strategic summary: ").append(output.strategicSummary()).append("\n\n");
        sb.append("Brand foundations: ").append(output.brandFoundations()).append("\n\n");
        sb.append("Verbal identity: ").append(output.verbalIdentity()).append("\n\n");
        sb.append("Visual identity summary: ").append(output.visualIdentitySummary()).append("\n\n");
        sb.append("Logo rationale: ").append(output.logoRationale()).append("\n\n");
        sb.append("Recommended applications: ")
            .append(String.join(", ", output.recommendedApplications())).append("\n\n");

        return sb.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize specialist output for evaluation prompt", e);
        }
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
            throw new IllegalStateException("Failed to parse PlaybookEvaluator output: " + e.getMessage() + "\nRaw: " + raw, e);
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
