package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.BriefDerivationExtension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BriefDerivationService implements BriefDerivationExtension {

    private static final Logger log = LoggerFactory.getLogger(BriefDerivationService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final TenantBriefConfig tenantBriefConfig;

    public BriefDerivationService(ChatClient.Builder builder,
                                   ObjectMapper objectMapper,
                                   TenantBriefConfig tenantBriefConfig) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.tenantBriefConfig = tenantBriefConfig;
    }

    @Override
    public List<DirectionBrief> derive(String engagementId,
                                        List<QuestionnaireQuestion> questions,
                                        List<QuestionnaireAnswer> answers) {
        Map<String, String> answerByQuestion = answers.stream()
            .collect(Collectors.toMap(QuestionnaireAnswer::getQuestionId, a -> cleanValue(a.getValueJson())));

        StringBuilder qa = new StringBuilder();
        for (QuestionnaireQuestion q : questions) {
            String answer = answerByQuestion.getOrDefault(q.getId(), "(no answer)");
            qa.append("Q: ").append(q.getPrompt()).append("\nA: ").append(answer).append("\n\n");
        }

        String raw = chatClient.prompt()
            .options(AnthropicChatOptions.builder()
                .model("claude-opus-5")
                .maxTokens(4000)
                .build())
            .system(tenantBriefConfig.systemPrompt())
            .user("Client intake responses:\n\n" + qa)
            .call()
            .content();

        return parseBriefs(engagementId, raw);
    }

    private List<DirectionBrief> parseBriefs(String engagementId, String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end = json.lastIndexOf("```");
                json = json.substring(start, end > start ? end : json.length()).strip();
            }

            JsonNode root = objectMapper.readTree(json);

            JsonNode fn = root.path("foundation");
            DirectionBrief.BrandFoundation foundation = new DirectionBrief.BrandFoundation(
                fn.path("differentiator").asText(""),
                fn.path("targetAudiencePersona").asText(""),
                fn.path("corePositioning").asText(""),
                fn.path("toneSpectrum").asText("")
            );

            JsonNode dirs = root.path("directions");
            List<DirectionBrief> briefs = new ArrayList<>();

            for (JsonNode d : dirs) {
                JsonNode b = d.path("brand");
                List<String> personality = new ArrayList<>();
                for (JsonNode p : b.path("personality")) personality.add(p.asText());
                List<String> ti = new ArrayList<>();
                for (JsonNode t : d.path("trainingInstructions")) ti.add(t.asText());

                DirectionBrief.BrandContext brand = new DirectionBrief.BrandContext(
                    b.path("name").asText(""),
                    b.path("industry").asText(""),
                    foundation.targetAudiencePersona(),
                    b.path("coreOffer").asText(""),
                    foundation.differentiator(),
                    personality,
                    b.path("tone").asText("")
                );

                DirectionBrief.CreativeDirection direction =
                    DirectionBrief.CreativeDirection.valueOf(d.path("direction").asText("ANCHORED"));

                briefs.add(new DirectionBrief(
                    engagementId, brand, foundation, direction, ti,
                    d.path("additionalContext").asText("")
                ));
            }
            return briefs;
        } catch (Exception e) {
            log.error("Brief derivation parse failed: {}", e.getMessage());
            throw new RuntimeException("Failed to parse direction briefs from Brain Engine response", e);
        }
    }

    private String cleanValue(String valueJson) {
        if (valueJson == null) return "(no answer)";
        try {
            JsonNode node = objectMapper.readTree(valueJson);
            if (node.isTextual()) return node.asText();
            if (node.isArray()) {
                List<String> items = new ArrayList<>();
                for (JsonNode item : node) items.add(item.asText());
                return String.join(", ", items);
            }
            if (node.isNumber()) return node.asText();
            if (node.has("blobPath")) return "(uploaded image)";
            return node.toString();
        } catch (Exception e) {
            return valueJson;
        }
    }
}
