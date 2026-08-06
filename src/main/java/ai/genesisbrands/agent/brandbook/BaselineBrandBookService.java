package ai.genesisbrands.agent.brandbook;

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
 * Baseline comparison for the A/B playground: same underlying model as BrandBookAgent,
 * but no persona, no craft rubric, no groundedness discipline — just a plain
 * instruction to write brand book prose from the brief and specialist outputs, the way
 * a generic chat request would. Isolates the value the orchestration/knowledge layers
 * add over a plain model call.
 */
@Service
public class BaselineBrandBookService {

    private static final Logger log = LoggerFactory.getLogger(BaselineBrandBookService.class);
    private static final String AGENT_ID = "brand-book";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    public BaselineBrandBookService(ChatClient.Builder chatClientBuilder,
                                     AgentProperties agentProperties,
                                     ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
    }

    public BaselineOutput generate(BrandBookInput input) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        DirectionBrief.BrandContext b = input.brief().brand();

        String prompt = """
            Write the client-facing brand book prose for this brand, given the brief and the already-final \
            specialist outputs below.

            Brand: %s
            Industry: %s
            Target audience: %s
            Core offer: %s

            Playbook (JSON): %s
            Copy Agent output (JSON): %s
            Visual Identity Agent output (JSON): %s
            Logo Agent output (JSON): %s

            Give me: a welcome note, how-to-use-this-guide orientation, logo usage guidelines, a list of logo \
            don'ts, color usage guidelines, typography usage guidelines, and a closing note.

            Return only raw JSON, no markdown fences, with exactly these keys, each a plain prose \
            string (not a nested object) unless noted otherwise: \
            welcomeNote (string), howToUseThisGuide (string), logoUsageGuidelines (string), \
            logoDonts (array of strings), colorUsageGuidelines (string), typographyUsageGuidelines (string), \
            closingNote (string).
            """.formatted(b.name(), b.industry(), b.targetAudience(), b.coreOffer(),
                toJson(input.playbook()), toJson(input.copy()), toJson(input.visualIdentity()), toJson(input.logo()));

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
                log.warn("BaselineBrandBookService output parse failed on attempt {}/2: {}", attempt, e.getMessage());
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
                asString(map.get("welcomeNote")),
                asString(map.get("howToUseThisGuide")),
                asString(map.get("logoUsageGuidelines")),
                (List<String>) map.getOrDefault("logoDonts", List.of()),
                asString(map.get("colorUsageGuidelines")),
                asString(map.get("typographyUsageGuidelines")),
                asString(map.get("closingNote"))
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
