package ai.genesisbrands.agent.playbook;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Baseline comparison for the A/B playground: same underlying model as PlaybookAgent,
 * but no persona, no craft rubric, no RAG precedent — just a plain instruction to
 * combine the brief and the three specialist outputs into a document, the way a
 * generic chat request would. Isolates the value the orchestration/knowledge layers
 * add over a plain model call.
 */
@Service
public class BaselinePlaybookService {

    private static final Logger log = LoggerFactory.getLogger(BaselinePlaybookService.class);
    private static final String AGENT_ID = "playbook";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    public BaselinePlaybookService(ChatClient.Builder chatClientBuilder,
                                    AgentProperties agentProperties,
                                    ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
    }

    public BaselineOutput generate(PlaybookInput input) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        DirectionBrief.BrandContext b = input.brief().brand();

        String prompt = """
            Combine the following brand brief and specialist outputs into a single brand playbook document.

            Brand: %s
            Industry: %s
            Target audience: %s
            Core offer: %s

            Copy Agent output (JSON): %s
            Visual Identity Agent output (JSON): %s
            Logo Agent output (JSON): %s

            Give me: a strategic summary, brand foundations, verbal identity, visual identity summary, \
            logo rationale, and a list of recommended applications.

            Return only raw JSON, no markdown fences, with exactly these keys, each a plain prose \
            string (not a nested object) unless noted otherwise: \
            strategicSummary (string), brandFoundations (string), verbalIdentity (string), \
            visualIdentitySummary (string), logoRationale (string), \
            recommendedApplications (array of strings).
            """.formatted(b.name(), b.industry(), b.targetAudience(), b.coreOffer(),
                toJson(input.copy()), toJson(input.visualIdentity()), toJson(input.logo()));

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
                log.warn("BaselinePlaybookService output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize specialist output for baseline prompt", e);
        }
    }

    @SuppressWarnings("unchecked")
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
                asString(map.get("strategicSummary")),
                asString(map.get("brandFoundations")),
                asString(map.get("verbalIdentity")),
                asString(map.get("visualIdentitySummary")),
                asString(map.get("logoRationale")),
                (List<String>) map.getOrDefault("recommendedApplications", List.of())
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse baseline output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }

    // The baseline prompt has no strict schema enforcement, so despite instructing
    // flat strings, the model occasionally nests an object for a field instead —
    // fall back to rendering it as JSON rather than crashing the whole comparison.
    private String asString(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
