package ai.genesisbrands.service;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
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

/**
 * Calls the Brain Engine (Opus 5) to derive three DirectionBriefs — ANCHORED, EVOLVED,
 * and DISRUPTIVE — from a completed set of questionnaire answers.
 */
@Service
public class BriefDerivationService {

    private static final Logger log = LoggerFactory.getLogger(BriefDerivationService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public BriefDerivationService(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
    }

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

        String systemPrompt = """
            You are Genesis AI — a brand strategy creative director.
            You will receive questionnaire responses from a new client intake.

            Work in two phases:

            PHASE 1 — Brand Foundation (direction-agnostic):
            First reason what is fundamentally and consistently true about this brand, regardless of creative direction.
            These truths must be derived explicitly and are shared equally across all three directions:
            - differentiator: what genuinely sets this brand apart from competitors (specific, not generic)
            - targetAudiencePersona: a vivid, specific description of the core customer (who they are, what they care about, their world)
            - corePositioning: the single brand truth — the territory this brand owns in the mind of its audience
            - toneSpectrum: the natural voice range of this brand (the full spectrum, not a single point — e.g. "authoritative but never cold; technical but always human")

            PHASE 2 — Three Creative Directions:
            Using the foundation, derive three distinct DirectionBriefs. Each direction applies the same foundation through a different creative lens.

            Return ONLY a valid JSON object — no markdown fences, no commentary:
            {
              "foundation": {
                "differentiator": "string",
                "targetAudiencePersona": "string",
                "corePositioning": "string",
                "toneSpectrum": "string"
              },
              "directions": [
                {
                  "direction": "ANCHORED",
                  "brand": {
                    "name": "string",
                    "industry": "string",
                    "coreOffer": "string",
                    "personality": ["trait1", "trait2", "trait3"],
                    "tone": "string"
                  },
                  "trainingInstructions": ["instruction1", "instruction2"],
                  "additionalContext": "string"
                },
                { "direction": "EVOLVED", "brand": { ... }, "trainingInstructions": [...], "additionalContext": "string" },
                { "direction": "DISRUPTIVE", "brand": { ... }, "trainingInstructions": [...], "additionalContext": "string" }
              ]
            }

            Direction meanings:
            - ANCHORED: authentic to the brand's existing equity; evolutionary, not revolutionary.
            - EVOLVED: builds on its foundations but pushes forward; sharper, more confident.
            - DISRUPTIVE: challenges category conventions; bold repositioning; reframes the conversation.

            Rules:
            - 'foundation' is derived once and shared — differentiator and targetAudiencePersona must be consistent across all directions.
            - 'brand.name', 'brand.industry', 'brand.coreOffer' are objective facts — identical across all three directions.
            - 'brand.personality' and 'brand.tone' shift to match each direction's creative stance.
            - 'trainingInstructions': 2–3 craft-level instructions specialist agents must follow for this direction.
            - 'additionalContext': the strategic rationale — WHY this direction, what creative territory it occupies.
            """;

        String userMessage = "Client intake responses:\n\n" + qa;

        String raw = chatClient.prompt()
            .options(AnthropicChatOptions.builder()
                .model("claude-opus-5")
                .maxTokens(4000)
                .build())
            .system(systemPrompt)
            .user(userMessage)
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

            // Extract the explicitly-reasoned foundation (direction-agnostic)
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

                // BrandContext is fully populated: direction-specific fields from 'brand',
                // canonical differentiator and targetAudience come from the shared foundation.
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
