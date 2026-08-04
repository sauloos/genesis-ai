package ai.genesisbrands.agent.copy;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.DirectionBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CopyAgent {

    private static final Logger log = LoggerFactory.getLogger(CopyAgent.class);
    private static final String AGENT_ID = "copy";

    // ── Runtime extraction seam: these fields move to AgentRuntime ────────────
    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();
    // ─────────────────────────────────────────────────────────────────────────

    private final String qdrantUrl;
    private final String qdrantApiKey;
    private final String collectionName;

    private String systemPrompt;

    public CopyAgent(ChatClient.Builder chatClientBuilder,
                     EmbeddingModel embeddingModel,
                     AgentProperties agentProperties,
                     ObjectMapper objectMapper,
                     @org.springframework.beans.factory.annotation.Value("${qdrant.rest.url:http://localhost:6333}") String qdrantUrl,
                     @org.springframework.beans.factory.annotation.Value("${qdrant.rest.api-key:}") String qdrantApiKey,
                     @org.springframework.beans.factory.annotation.Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}") String collectionName) {
        this.chatClient = chatClientBuilder.build();
        this.embeddingModel = embeddingModel;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.qdrantUrl = qdrantUrl;
        this.qdrantApiKey = qdrantApiKey;
        this.collectionName = collectionName;
        this.systemPrompt = loadSystemPrompt();
    }

    // ── Public interface (defines the SpecialistAgent contract) ───────────────

    public CopyOutput execute(DirectionBrief brief) {
        return run(brief, null, null, 1);
    }

    public CopyOutput executeWithRevision(DirectionBrief brief, CopyOutput previous, AgentRevision revision) {
        return run(brief, previous, revision, previous.iteration() + 1);
    }

    // ── Runtime extraction seam: assemblePrompt moves to AgentRuntime ─────────

    private CopyOutput run(DirectionBrief brief, CopyOutput previous, AgentRevision revision, int iteration) {
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
                log.warn("CopyAgent output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    // ── Runtime extraction seam: retrievePrecedent moves to AgentRuntime ──────

    @SuppressWarnings("unchecked")
    private List<String> retrievePrecedent(DirectionBrief brief, int topK) {
        try {
            DirectionBrief.BrandContext b = brief.brand();
            String query = String.join(" ", b.name(), b.industry(), b.coreOffer(), b.tone());
            float[] vector = embeddingModel.embed(query);

            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float f : vector) vectorList.add(f);

            Map<String, Object> body = Map.of(
                "vector", vectorList,
                "limit", topK,
                "with_payload", true,
                "filter", Map.of("must", List.of(
                    Map.of("key", "layer", "match", Map.of("value", "layer2"))
                ))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (qdrantApiKey != null && !qdrantApiKey.isBlank())
                headers.set("api-key", qdrantApiKey);

            String url = qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points/search";
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("result");
            return results.stream()
                .map(r -> (Map<String, Object>) r.get("payload"))
                .filter(p -> p.containsKey("text"))
                .map(p -> (String) p.get("text"))
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Layer 2 retrieval failed, proceeding without precedent: {}", e.getMessage());
            return List.of();
        }
    }

    private String assemblePrompt(DirectionBrief brief, List<String> precedent,
                                   CopyOutput previous, AgentRevision revision) {
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
            sb.append("## Precedent — Similar Brand Copy\n");
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
            sb.append("Tagline: ").append(previous.tagline()).append("\n");
            sb.append("Mission: ").append(previous.missionStatement()).append("\n\n");
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

    private CopyOutput parse(String raw, String engagementId, int iteration) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            // Claude occasionally emits raw newlines inside multi-paragraph string
            // values (e.g. brandStory) instead of escaping them as \n — tolerate that
            // rather than failing the whole parse.
            Map<String, Object> map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);

            Map<String, Object> tgMap = (Map<String, Object>) map.get("toneGuide");
            List<String> principles = (List<String>) tgMap.get("principles");
            List<Map<String, String>> exMaps = (List<Map<String, String>>) tgMap.get("examples");
            List<CopyOutput.ToneExample> examples = exMaps.stream()
                .map(e -> new CopyOutput.ToneExample(e.get("principle"), e.get("doThis"), e.get("notThis")))
                .collect(Collectors.toList());

            return new CopyOutput(
                engagementId,
                (String) map.get("tagline"),
                (String) map.get("missionStatement"),
                (String) map.get("brandStory"),
                (String) map.get("elevatorPitch"),
                new CopyOutput.ToneGuide(principles, examples),
                (String) map.get("reasoning"),
                iteration
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse CopyAgent output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
