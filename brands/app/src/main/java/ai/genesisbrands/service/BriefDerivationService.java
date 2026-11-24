package ai.genesisbrands.service;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.BriefDerivationExtension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Derives the three direction briefs straight from a questionnaire transcript.
 *
 * <p>This is the legacy path, and kept deliberately. Engagements taken before the Purpose Playbook
 * instrument existed ran on questionnaires with no Layer 1 signal keys, so their answers carry no
 * name the reasoning modules recognise — running the playbook agent on one would produce a
 * confident document about nothing. {@link EngagementOrchestratorService} therefore chooses on the
 * questionnaire: signal keys present, {@link PurposePlaybookBriefDerivation}; absent, this.
 *
 * <p>It also still backs the core {@code BriefDerivationExtension} SPI, which the Playground's
 * derive-briefs endpoint calls with raw questions and answers.
 */
@Service
public class BriefDerivationService implements BriefDerivationExtension {

    private static final Logger log = LoggerFactory.getLogger(BriefDerivationService.class);

    /**
     * Config-only agent id — no {@code CoreAgent} bean, because this is a step inside the
     * engagement pipeline rather than a surface anyone selects. Same arrangement as
     * {@code direction-brief}, its Purpose Playbook successor, including the limitation recorded
     * there: no owning agent means the agents admin page cannot reach this key, so the
     * {@code agent.yml} values below are the only ones it will ever run with.
     */
    static final String AGENT_ID = "brief-derivation";

    /** Anthropic's stop reason for a response that ran into {@code max_tokens}. */
    private static final String MAX_TOKENS_FINISH = "max_tokens";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    public BriefDerivationService(ChatClient.Builder builder,
                                   ObjectMapper objectMapper,
                                   AgentProperties agentProperties) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
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

        // Read per request, never cached in a field, so an admin change applies without a restart.
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);

        ChatResponse response = chatClient.prompt()
            .options(AnthropicChatOptions.builder()
                .model(config.getModel())
                .maxTokens(config.getMaxTokens())
                .build())
            .system(agentProperties.systemPrompt(AGENT_ID))
            .user("Client intake responses:\n\n" + qa)
            .call()
            .chatResponse();

        return parseBriefs(engagementId, text(response, config));
    }

    /**
     * The response text, with a truncated response named as truncated.
     *
     * <p>Without this, hitting the cap arrives downstream as "Failed to parse direction briefs" on
     * JSON that is perfectly well-formed up to where it stops — which sends whoever is debugging it
     * at the prompt or the model rather than at {@code max-tokens}.
     */
    private String text(ChatResponse response, AgentProperties.AgentConfig config) {
        Generation result = response == null ? null : response.getResult();
        String raw = result == null || result.getOutput() == null ? null : result.getOutput().getText();
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("Brief derivation returned an empty response.");
        }
        String finishReason = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
        if (MAX_TOKENS_FINISH.equalsIgnoreCase(finishReason)) {
            throw new RuntimeException("The briefs were cut off at the " + config.getMaxTokens()
                + "-token cap after " + raw.length() + " characters, so they are incomplete rather "
                + "than malformed. Raise max-tokens for " + AGENT_ID + ".");
        }
        return raw;
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
                    d.path("additionalContext").asText(""),
                    // No Purpose Playbook on this path — that is what makes it the legacy path.
                    null
                ));
            }
            if (briefs.isEmpty()) {
                log.error("Brief derivation returned zero directions for engagement {}. Raw response:\n{}",
                    engagementId, raw);
                throw new RuntimeException("Brief derivation returned zero directions — expected 3");
            }
            return briefs;
        } catch (Exception e) {
            log.error("Brief derivation parse failed for engagement {}: {}. Raw response:\n{}",
                engagementId, e.getMessage(), raw);
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
