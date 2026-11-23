package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.agent.core.EvaluationResult;
import ai.genesisbrands.service.Layer1Service;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Genesis AI's review of a Purpose Playbook draft, against
 * {@code evaluation/purpose-playbook-rubric.yaml}.
 *
 * <p>Evaluated harder than any downstream artefact, and deliberately so: a brand playbook that
 * fails its rubric costs one regeneration, whereas a purpose playbook that passes while being
 * wrong propagates a false premise into three directions, five specialist outputs, the client's
 * Consultant context and the Layer 2 corpus. The rubric's own instruction is to prefer rejecting
 * a plausible document over accepting a flattering one.
 *
 * <p>Two stages. {@link PurposePlaybookGuard} decides the mechanical rejection triggers in code
 * and, when it finds any, returns REVISE without a model call — there is nothing for judgement to
 * add to "section X is missing". Only a draft that is structurally sound reaches the model, which
 * then spends its whole attention on the things that actually need reading: whether the mission is
 * derived or relabelled, whether a competitor critique matches the page, whether a thin signal has
 * been smoothed into confident prose.
 */
@Service
public class PurposePlaybookEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PurposePlaybookEvaluator.class);

    static final String AGENT_ID = "purpose-playbook-evaluator";
    static final String RUBRIC_KEY = "evaluation/purpose-playbook-rubric.yaml";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final Layer1Service layer1Service;
    private final ObjectMapper objectMapper;
    private final PurposePlaybookGuard guard;

    public PurposePlaybookEvaluator(ChatClient.Builder chatClientBuilder,
                                    AgentProperties agentProperties,
                                    Layer1Service layer1Service,
                                    ObjectMapper objectMapper,
                                    PurposePlaybookGuard guard) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.layer1Service = layer1Service;
        this.objectMapper = objectMapper;
        this.guard = guard;
    }

    public EvaluationResult evaluate(PurposePlaybookInput input, PurposePlaybookOutput output) {
        List<String> violations = guard.check(input, output);
        if (!violations.isEmpty()) {
            log.info("Purpose playbook draft {} failed {} mechanical check(s); returning REVISE without "
                + "a model call", input.engagementId(), violations.size());
            return new EvaluationResult(
                EvaluationResult.Verdict.REVISE,
                "The draft fails " + violations.size() + " check(s) that are decided mechanically, "
                    + "before any judgement is applied.",
                new AgentRevision(
                    "Fix these first — each is a rejection trigger in the rubric and none of them is "
                        + "a matter of opinion.",
                    violations,
                    List.of()));
        }

        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);
        String userPrompt = assemblePrompt(input, output);

        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = chatClient.prompt()
                .system(agentProperties.systemPrompt(AGENT_ID))
                .user(userPrompt)
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
                log.warn("PurposePlaybookEvaluator output parse failed on attempt {}/2: {}",
                    attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private String assemblePrompt(PurposePlaybookInput input, PurposePlaybookOutput output) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Evaluation rubric\n\n").append(layer1Service.getModule(RUBRIC_KEY)).append("\n\n");

        sb.append("## The client's answers — the only ground truth\n\n");
        sb.append("Every assertion in the draft must trace to one of these, to a page the agent "
            + "actually read, or to a visible derivation across several of them. A signal that is "
            + "not here was not collected.\n\n");
        sb.append(json(input.signals())).append("\n\n");

        if (!input.expectedSignals().isEmpty()) {
            sb.append("Signals this questionnaire asked for: ")
                .append(String.join(", ", input.expectedSignals())).append("\n\n");
        }

        sb.append("## The draft\n\n").append(json(output)).append("\n\n");

        sb.append("## Checks already passed\n\n");
        sb.append("Section completeness, empty bodies, unsourced sections, the never-list's "
            + "verbatim words, \"avatar\", labelled CAUSE or Values Statement sections, invented "
            + "colour values and a missing look territory have all been checked in code and are "
            + "clean. Do not re-report them. Spend your attention on derivation, groundedness, "
            + "honest gaps and whether this document could actually brief the work.\n\n");

        return sb.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize for the evaluation prompt", e);
        }
    }

    @SuppressWarnings("unchecked")
    private EvaluationResult parse(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            Map<String, Object> map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);

            EvaluationResult.Verdict verdict = EvaluationResult.Verdict.valueOf((String) map.get("verdict"));
            String summary = (String) map.get("summary");

            AgentRevision revision = null;
            Map<String, Object> revMap = (Map<String, Object>) map.get("revision");
            if (revMap != null) {
                revision = new AgentRevision(
                    (String) revMap.get("feedback"),
                    (List<String>) revMap.getOrDefault("specificIssues", List.of()),
                    (List<String>) revMap.getOrDefault("fieldsToRevise", List.of())
                );
            }

            return new EvaluationResult(verdict, summary, revision);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to parse PurposePlaybookEvaluator output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }
}
