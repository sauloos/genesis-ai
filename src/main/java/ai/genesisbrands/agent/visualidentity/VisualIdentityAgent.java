package ai.genesisbrands.agent.visualidentity;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.service.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VisualIdentityAgent {

    private static final Logger log = LoggerFactory.getLogger(VisualIdentityAgent.class);
    private static final String AGENT_ID = "visual-identity";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final RetrievalService retrievalService;

    private String systemPrompt;

    public VisualIdentityAgent(ChatClient.Builder chatClientBuilder,
                                AgentProperties agentProperties,
                                ObjectMapper objectMapper,
                                RetrievalService retrievalService) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.systemPrompt = loadSystemPrompt();
    }

    // ── Public interface (defines the SpecialistAgent contract) ───────────────

    public VisualIdentityOutput execute(DirectionBrief brief) {
        return run(brief, null, null, 1);
    }

    public VisualIdentityOutput executeWithRevision(DirectionBrief brief, VisualIdentityOutput previous, AgentRevision revision) {
        return run(brief, previous, revision, previous.iteration() + 1);
    }

    // ── Runtime extraction seam: assemblePrompt moves to AgentRuntime ─────────

    private VisualIdentityOutput run(DirectionBrief brief, VisualIdentityOutput previous, AgentRevision revision, int iteration) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);

        List<String> precedent = config.getRag().isEnabled()
            ? retrievePrecedent(brief, config.getRag().getTopK())
            : List.of();

        String userPrompt = assemblePrompt(brief, precedent, previous, revision);

        // Claude occasionally emits structurally malformed JSON (a dropped comma,
        // stray character) — retry once before failing the request, since a
        // second sample is very likely well-formed.
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
                return parse(raw, brief.engagementId(), iteration);
            } catch (IllegalStateException e) {
                lastFailure = e;
                log.warn("VisualIdentityAgent output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private List<String> retrievePrecedent(DirectionBrief brief, int topK) {
        DirectionBrief.BrandContext b = brief.brand();
        String query = String.join(" ", b.name(), b.industry(), b.coreOffer(), b.tone());
        return retrievalService.retrieveForAgent(query, AGENT_ID, topK);
    }

    private String assemblePrompt(DirectionBrief brief, List<String> precedent,
                                   VisualIdentityOutput previous, AgentRevision revision) {
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

        if (!brief.trainingInstructions().isEmpty()) {
            sb.append("## Specific Instructions\n");
            brief.trainingInstructions().forEach(i -> sb.append("- ").append(i).append("\n"));
            sb.append("\n");
        }

        if (!precedent.isEmpty()) {
            sb.append("## Precedent — Similar Brand Visual Systems\n");
            sb.append("Use these as stylistic reference only — not to copy:\n\n");
            for (int i = 0; i < precedent.size(); i++) {
                sb.append("Example ").append(i + 1).append(":\n").append(precedent.get(i)).append("\n\n");
            }
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
            sb.append("\nPrevious output for reference:\n");
            sb.append("Typography: ").append(previous.typography().headlineFont())
                .append(" / ").append(previous.typography().bodyFont()).append("\n");
            sb.append("Mood direction: ").append(previous.moodDirection()).append("\n\n");
        }

        return sb.toString();
    }

    // ── Runtime extraction seam: loadSystemPrompt + parse move to AgentRuntime ─

    private String loadSystemPrompt() {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        try {
            return new ClassPathResource(config.getSystemPrompt())
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load system prompt: " + config.getSystemPrompt(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private VisualIdentityOutput parse(String raw, String engagementId, int iteration) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            // Claude occasionally emits raw newlines inside multi-sentence string
            // values (e.g. moodDirection) instead of escaping them as \n — tolerate
            // that rather than failing the whole parse.
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

            return new VisualIdentityOutput(
                engagementId,
                palette,
                typography,
                (String) map.get("moodDirection"),
                (String) map.get("reasoning"),
                iteration
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse VisualIdentityAgent output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
