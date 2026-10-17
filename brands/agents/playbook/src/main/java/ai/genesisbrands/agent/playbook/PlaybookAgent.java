package ai.genesisbrands.agent.playbook;

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

@Service
public class PlaybookAgent {

    private static final Logger log = LoggerFactory.getLogger(PlaybookAgent.class);
    private static final String AGENT_ID = "playbook";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final RetrievalService retrievalService;

    private final String systemPrompt;

    public PlaybookAgent(ChatClient.Builder chatClientBuilder,
                          AgentProperties agentProperties,
                          ObjectMapper objectMapper,
                          RetrievalService retrievalService) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.systemPrompt = loadSystemPrompt();
    }

    public PlaybookOutput execute(PlaybookInput input) {
        return run(input, null, null, 1);
    }

    public PlaybookOutput executeWithRevision(PlaybookInput input, PlaybookOutput previous, AgentRevision revision) {
        return run(input, previous, revision, previous.iteration() + 1);
    }

    private PlaybookOutput run(PlaybookInput input, PlaybookOutput previous, AgentRevision revision, int iteration) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        DirectionBrief brief = input.brief();

        List<String> precedent = config.getRag().isEnabled()
            ? retrievePrecedent(brief, config.getRag().getTopK())
            : List.of();

        String userPrompt = assemblePrompt(input, precedent, previous, revision);

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
                log.warn("PlaybookAgent output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private List<String> retrievePrecedent(DirectionBrief brief, int topK) {
        DirectionBrief.BrandContext b = brief.brand();
        String query = String.join(" ", b.name(), b.industry(), b.coreOffer(), b.tone());
        return retrievalService.retrieveForAgent(query, AGENT_ID, topK);
    }

    private String assemblePrompt(PlaybookInput input, List<String> precedent,
                                   PlaybookOutput previous, AgentRevision revision) {
        DirectionBrief brief = input.brief();
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

        sb.append("## Accepted Specialist Outputs — synthesize from these, do not invent beyond them\n\n");
        sb.append("### Copy Agent output\n").append(toJson(input.copy())).append("\n\n");
        sb.append("### Visual Identity Agent output\n").append(toJson(input.visualIdentity())).append("\n\n");
        sb.append("### Logo Agent output\n").append(toJson(input.logo())).append("\n\n");

        if (!brief.trainingInstructions().isEmpty()) {
            sb.append("## Specific Instructions\n");
            brief.trainingInstructions().forEach(i -> sb.append("- ").append(i).append("\n"));
            sb.append("\n");
        }

        if (!precedent.isEmpty()) {
            sb.append("## Precedent — Similar Brand Playbooks\n");
            sb.append("Use these as structural/stylistic reference only — not to copy:\n\n");
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
            sb.append("Strategic summary: ").append(previous.strategicSummary()).append("\n\n");
        }

        return sb.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize specialist output for prompt assembly", e);
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

    @SuppressWarnings("unchecked")
    private PlaybookOutput parse(String raw, String engagementId, int iteration) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            // Claude occasionally emits raw newlines inside multi-paragraph string
            // values instead of escaping them as \n — tolerate that rather than
            // failing the whole parse.
            Map<String, Object> map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);

            List<String> recommendedApplications = (List<String>) map.getOrDefault("recommendedApplications", List.of());

            return new PlaybookOutput(
                engagementId,
                (String) map.get("strategicSummary"),
                (String) map.get("brandFoundations"),
                (String) map.get("verbalIdentity"),
                (String) map.get("visualIdentitySummary"),
                (String) map.get("logoRationale"),
                recommendedApplications,
                (String) map.get("reasoning"),
                iteration
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse PlaybookAgent output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
