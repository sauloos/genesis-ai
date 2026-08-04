package ai.genesisbrands.agent.visualidentity;

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
import java.util.stream.Collectors;

/**
 * Baseline comparison for the A/B playground: same underlying model as
 * VisualIdentityAgent, but no persona, no craft rubric, no RAG precedent, no training
 * instructions — just the raw brand facts, the way a generic chat request would use
 * them. Isolates the value the orchestration/knowledge layers add over a plain model call.
 */
@Service
public class BaselineVisualIdentityService {

    private static final Logger log = LoggerFactory.getLogger(BaselineVisualIdentityService.class);
    private static final String AGENT_ID = "visual-identity";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    public BaselineVisualIdentityService(ChatClient.Builder chatClientBuilder,
                                          AgentProperties agentProperties,
                                          ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
    }

    public BaselineVisualIdentityOutput generate(DirectionBrief brief) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        DirectionBrief.BrandContext b = brief.brand();

        String prompt = """
            Design a visual identity for this company:

            Name: %s
            Industry: %s
            Target audience: %s
            Core offer: %s

            Give me: a colour palette (4 to 6 swatches, each with a name, hex code, role \
            of primary/secondary/accent/neutral, and a short rationale), a typography \
            pairing (a headline font, a body font, and a rationale for the pairing), and \
            a mood direction (a few sentences).

            Return only raw JSON, no markdown fences, with exactly these keys: \
            colorPalette (array of objects with name, hex, role, rationale), \
            typography (object with headlineFont, bodyFont, pairingRationale), moodDirection.
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
                log.warn("BaselineVisualIdentityService output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    @SuppressWarnings("unchecked")
    private BaselineVisualIdentityOutput parse(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            Map<String, Object> map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);

            List<Map<String, String>> paletteMaps = (List<Map<String, String>>) map.get("colorPalette");
            List<VisualIdentityOutput.ColorSwatch> palette = paletteMaps.stream()
                .map(p -> new VisualIdentityOutput.ColorSwatch(
                    p.get("name"), p.get("hex"), p.get("role"), p.get("rationale")))
                .collect(Collectors.toList());

            Map<String, String> typoMap = (Map<String, String>) map.get("typography");
            VisualIdentityOutput.Typography typography = new VisualIdentityOutput.Typography(
                typoMap.get("headlineFont"), typoMap.get("bodyFont"), typoMap.get("pairingRationale"));

            return new BaselineVisualIdentityOutput(palette, typography, (String) map.get("moodDirection"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse baseline visual identity output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
