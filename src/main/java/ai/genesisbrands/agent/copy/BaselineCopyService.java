package ai.genesisbrands.agent.copy;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Baseline comparison for the A/B playground: same underlying model as CopyAgent,
 * but no persona, no craft rubric, no RAG precedent, no training instructions —
 * just the raw brand facts, the way a generic chat request would use them. Isolates
 * the value the orchestration/knowledge layers add over a plain model call.
 */
@Service
public class BaselineCopyService {

    private static final Logger log = LoggerFactory.getLogger(BaselineCopyService.class);
    private static final String AGENT_ID = "copy";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    public BaselineCopyService(ChatClient.Builder chatClientBuilder,
                                AgentProperties agentProperties,
                                ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
    }

    public BaselineOutput generate(DirectionBrief brief) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        DirectionBrief.BrandContext b = brief.brand();

        String prompt = """
            Write marketing copy for this company:

            Name: %s
            Industry: %s
            Target audience: %s
            Core offer: %s

            Give me: a tagline, a mission statement, a brand story (2-3 paragraphs), \
            and an elevator pitch.

            Return only raw JSON, no markdown fences, with exactly these keys: \
            tagline, missionStatement, brandStory, elevatorPitch.
            """.formatted(b.name(), b.industry(), b.targetAudience(), b.coreOffer());

        // Claude occasionally emits structurally malformed JSON (a dropped comma,
        // stray character) — retry once before failing the request, since a
        // second sample is very likely well-formed.
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
                log.warn("BaselineCopyService output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private BaselineOutput parse(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            Map<String, Object> map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);
            return new BaselineOutput(
                (String) map.get("tagline"),
                (String) map.get("missionStatement"),
                (String) map.get("brandStory"),
                (String) map.get("elevatorPitch")
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse baseline output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
