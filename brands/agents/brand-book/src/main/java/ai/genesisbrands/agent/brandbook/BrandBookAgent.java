package ai.genesisbrands.agent.brandbook;

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
import java.util.List;
import java.util.Map;

/**
 * Writes the client-facing prose for the Brand Book Assembly deliverable: welcome
 * note, orientation, and usage guidelines for logo/color/type. All creative decisions
 * (tagline, palette, fonts, logo concept) are already final by the time this agent
 * runs — its job is narrower than Playbook's: explain how to use what's already been
 * decided, never re-decide it. BrandBookTemplateRenderer combines this prose with the exact
 * facts from the source specialist outputs to produce the PDF.
 */
@Service
public class BrandBookAgent {

    private static final Logger log = LoggerFactory.getLogger(BrandBookAgent.class);
    private static final String AGENT_ID = "brand-book";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final RetrievalService retrievalService;

    private final String systemPrompt;

    public BrandBookAgent(ChatClient.Builder chatClientBuilder,
                           AgentProperties agentProperties,
                           ObjectMapper objectMapper,
                           RetrievalService retrievalService) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.systemPrompt = loadSystemPrompt();
    }

    public BrandBookOutput execute(BrandBookInput input) {
        return run(input, null, null, 1);
    }

    public BrandBookOutput executeWithRevision(BrandBookInput input, BrandBookOutput previous, AgentRevision revision) {
        return run(input, previous, revision, previous.iteration() + 1);
    }

    private BrandBookOutput run(BrandBookInput input, BrandBookOutput previous, AgentRevision revision, int iteration) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        DirectionBrief brief = input.brief();

        DirectionBrief.BrandContext b = brief.brand();
        List<String> precedent = config.getRag().isEnabled()
            ? retrievalService.retrieveForAgent(
                String.join(" ", b.name(), b.industry(), b.coreOffer(), b.tone()),
                AGENT_ID, config.getRag().getTopK())
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
                log.warn("BrandBookAgent output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private String assemblePrompt(BrandBookInput input, List<String> precedent,
                                   BrandBookOutput previous, AgentRevision revision) {
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

        if (!precedent.isEmpty()) {
            sb.append("## Knowledge & Precedent\n");
            precedent.forEach(p -> sb.append(p).append("\n\n"));
        }

        sb.append("## Already-Final Creative Decisions — explain these, never re-decide them\n\n");
        sb.append("### Playbook (strategic narrative)\n").append(toJson(input.playbook())).append("\n\n");
        sb.append("### Copy Agent output\n").append(toJson(input.copy())).append("\n\n");
        sb.append("### Visual Identity Agent output\n").append(toJson(input.visualIdentity())).append("\n\n");
        sb.append("### Logo Agent output\n").append(toJson(input.logo())).append("\n\n");

        if (!brief.trainingInstructions().isEmpty()) {
            sb.append("## Specific Instructions\n");
            brief.trainingInstructions().forEach(i -> sb.append("- ").append(i).append("\n"));
            sb.append("\n");
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
            sb.append("\nPrevious welcome note for reference:\n").append(previous.welcomeNote()).append("\n\n");
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
    private BrandBookOutput parse(String raw, String engagementId, int iteration) {
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

            List<String> logoDonts = (List<String>) map.getOrDefault("logoDonts", List.of());

            return new BrandBookOutput(
                engagementId,
                (String) map.get("welcomeNote"),
                (String) map.get("welcomeLetterOpening"),
                (String) map.get("howToUseThisGuide"),
                (String) map.get("logoUsageGuidelines"),
                logoDonts,
                (String) map.get("colorUsageGuidelines"),
                (String) map.get("typographyUsageGuidelines"),
                (String) map.get("typefaceRationale"),
                (String) map.get("imageryGuidance"),
                (String) map.get("closingNote"),
                (String) map.get("reasoning"),
                iteration
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse BrandBookAgent output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
