package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.service.Layer1Service;
import ai.genesisbrands.service.RetrievalService;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the Purpose Playbook: the single per-engagement document, half discovery and half
 * strategy, that the three creative directions and all five specialist agents are briefed from.
 *
 * <p>Three Layer 1 modules go into the prompt and are the real specification of the work —
 * the structure of the document, how to read the taste answers, and how the document briefs the
 * directions. They are fetched as text and never parsed here, which is the point: a change of
 * method is a change to the knowledge base, not a deploy.
 *
 * <p>This agent runs once per engagement, upstream of everything, and a false premise it accepts
 * propagates into three directions, five specialist outputs, the client's Consultant context and
 * the Layer 2 corpus. That is why it is configured on the orchestrator's model rather than the
 * specialists', and why its evaluation loop is stricter than any downstream agent's.
 */
@Service
public class PurposePlaybookAgent {

    private static final Logger log = LoggerFactory.getLogger(PurposePlaybookAgent.class);

    static final String AGENT_ID = "purpose-playbook";
    static final String STRUCTURE_KEY = "assembly/purpose-playbook-structure.yaml";
    static final String LOOK_KIT_KEY = "directions/look-and-feel-kit.yaml";
    static final String DIRECTION_KEY = "directions/purpose-to-direction.yaml";

    /** Anthropic's stop reason for a response that ran into {@code max_tokens}. */
    private static final String MAX_TOKENS_FINISH = "max_tokens";

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final Layer1Service layer1Service;
    private final RetrievalService retrievalService;
    private final SignalDigest signalDigest;
    private final ObservedPages observedPages;

    public PurposePlaybookAgent(ChatClient.Builder chatClientBuilder,
                                AgentProperties agentProperties,
                                ObjectMapper objectMapper,
                                Layer1Service layer1Service,
                                RetrievalService retrievalService,
                                SignalDigest signalDigest,
                                ObservedPages observedPages) {
        this.chatClient = chatClientBuilder.build();
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.layer1Service = layer1Service;
        this.retrievalService = retrievalService;
        this.signalDigest = signalDigest;
        this.observedPages = observedPages;
    }

    public PurposePlaybookOutput execute(PurposePlaybookInput input) {
        return run(input, null, null, 1);
    }

    public PurposePlaybookOutput executeWithRevision(PurposePlaybookInput input,
                                                     PurposePlaybookOutput previous,
                                                     AgentRevision revision) {
        return run(input, previous, revision, previous.iteration() + 1);
    }

    private PurposePlaybookOutput run(PurposePlaybookInput input, PurposePlaybookOutput previous,
                                      AgentRevision revision, int iteration) {
        AgentProperties.AgentConfig config = agentProperties.get(AGENT_ID);

        String structure = layer1Service.getModule(STRUCTURE_KEY);
        SignalDigest.Digest digest = signalDigest.of(input, structure);

        // Reading the named pages is a requirement of the method, not an enrichment: the rubric
        // rejects a competitor critiqued without one having been read.
        List<ObservedPages.Observation> pages = observedPages.readAll(input.signals());

        List<String> precedent = config.getRag().isEnabled()
            ? retrievePrecedent(input, config.getRag().getTopK())
            : List.of();

        String userPrompt = assemblePrompt(input, structure, digest, pages, precedent, previous, revision);

        // Claude occasionally emits structurally malformed JSON (a dropped comma, a stray
        // character) — retry once before failing, since a second sample is very likely clean.
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = generate(config, userPrompt);
            try {
                return parse(raw, input.engagementId(), iteration);
            } catch (IllegalStateException e) {
                lastFailure = e;
                log.warn("PurposePlaybookAgent output parse failed on attempt {}/2: {}", attempt, e.getMessage());
            }
        }
        throw lastFailure;
    }

    /**
     * One generation, streamed and reassembled.
     *
     * <p>Streaming is not an optimisation here, it is the only thing that works. This agent asks
     * for a whole document in one response — tens of thousands of output tokens, many times any
     * other agent in the platform — and a single non-streaming request that long sits past the
     * API's non-streaming window and never returns: the request thread parks in
     * {@code Mono.block()} with no timeout, and the caller eventually gives up while the server is
     * still waiting. A streamed response keeps data flowing, so neither end idles.
     *
     * <p>The caller still gets one complete string, because the document is parsed as a whole and
     * the mechanical guard runs over the finished object. Nothing downstream sees chunks.
     *
     * <p>Responses are collected rather than text chunks so the finish reason survives. A document
     * cut off at the token cap is still well-formed text and fails much later as an unhelpful
     * "expected close marker for Object", sending the caller looking for a JSON bug that is not
     * there; the cap is worth naming at the point it is hit, because the fix is a config change
     * and not another ten-minute sample.
     */
    private String generate(AgentProperties.AgentConfig config, String userPrompt) {
        List<ChatResponse> responses = chatClient.prompt()
            .system(agentProperties.systemPrompt(AGENT_ID))
            .user(userPrompt)
            .options(AnthropicChatOptions.builder()
                .model(config.getModel())
                .maxTokens(config.getMaxTokens())
                .build())
            .stream()
            .chatResponse()
            .collectList()
            .block();
        if (responses == null || responses.isEmpty()) {
            throw new IllegalStateException("The model returned an empty response.");
        }

        StringBuilder document = new StringBuilder();
        String finishReason = null;
        for (ChatResponse response : responses) {
            Generation result = response.getResult();
            if (result == null) {
                continue;
            }
            String text = result.getOutput() == null ? null : result.getOutput().getText();
            if (text != null) {
                document.append(text);
            }
            String reason = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
            if (reason != null && !reason.isBlank()) {
                finishReason = reason;
            }
        }

        if (MAX_TOKENS_FINISH.equalsIgnoreCase(finishReason)) {
            throw new IllegalStateException("The document was cut off at the "
                + config.getMaxTokens() + "-token cap after " + document.length()
                + " characters, so it is incomplete rather than malformed. Raise max-tokens for "
                + AGENT_ID + " (the model allows far more) — retrying only samples the same cap.");
        }
        if (document.isEmpty()) {
            throw new IllegalStateException("The model returned an empty response.");
        }
        return document.toString();
    }

    /**
     * Layer 2 precedent for an engagement that has no brief yet — so the query is built from the
     * discovery answers that describe the business, not from a brand context object that does not
     * exist at this point in the pipeline.
     */
    private List<String> retrievePrecedent(PurposePlaybookInput input, int topK) {
        String query = String.join(" ",
            input.text("setup.sector"),
            input.text("setup.oneLiner"),
            input.text("setup.stage"),
            input.text("best.usp")).strip();
        if (query.isBlank()) {
            return List.of();
        }
        return retrievalService.retrieveForAgent(query, AGENT_ID, topK);
    }

    String assemblePrompt(PurposePlaybookInput input, String structure, SignalDigest.Digest digest,
                          List<ObservedPages.Observation> pages, List<String> precedent,
                          PurposePlaybookOutput previous, AgentRevision revision) {
        StringBuilder sb = new StringBuilder();

        sb.append("## The document you are writing\n\n")
            .append(structure).append("\n\n");

        sb.append("## How to read the taste answers\n\n")
            .append(layer1Service.getModule(LOOK_KIT_KEY)).append("\n\n");

        sb.append("## How this document briefs the three directions\n\n")
            .append(layer1Service.getModule(DIRECTION_KEY)).append("\n\n");

        sb.append("## The client's answers\n\n");
        sb.append("Keyed by signal. Repeat blocks are grouped under their instance heading; a "
            + "look-and-feel value is shown with the design term behind it.\n\n");
        sb.append(digest.rendered()).append("\n");

        appendAbsences(sb, digest);

        if (!pages.isEmpty()) {
            sb.append("## Pages read first-hand\n\n");
            sb.append("These are the only pages you have seen. You may describe what is on them; "
                + "you may not describe a page not listed here, and where one is marked unread you "
                + "say so rather than inferring its contents.\n\n");
            for (ObservedPages.Observation page : pages) {
                sb.append("### ").append(page.signalKey()).append(" — ").append(page.url());
                if (!page.read()) {
                    sb.append(" (NOT READ)");
                }
                sb.append("\n").append(page.rendered()).append("\n");
            }
        }

        if (!precedent.isEmpty()) {
            sb.append("## Precedent — comparable engagements\n\n");
            sb.append("Structural and tonal reference only. Never carry a precedent's content, "
                + "names or figures into this client's document.\n\n");
            for (int i = 0; i < precedent.size(); i++) {
                sb.append("Example ").append(i + 1).append(":\n").append(precedent.get(i)).append("\n\n");
            }
        }

        if (!input.trainingInstructions().isEmpty()) {
            sb.append("## Specific instructions from the facilitator\n");
            input.trainingInstructions().forEach(i -> sb.append("- ").append(i).append("\n"));
            sb.append("\n");
        }

        if (input.additionalContext() != null && !input.additionalContext().isBlank()) {
            sb.append("## Additional context\n").append(input.additionalContext()).append("\n\n");
        }

        appendRevision(sb, previous, revision);
        return sb.toString();
    }

    /**
     * The three absences, kept apart. Conflating them is how a confidence map ends up claiming the
     * client failed to answer something they were never asked.
     */
    private void appendAbsences(StringBuilder sb, SignalDigest.Digest digest) {
        sb.append("## What is absent\n\n");
        if (!digest.hasGaps()) {
            sb.append("Every signal the structure module derives from was collected and answered "
                + "at length. The confidence map still has to be written — say which fields rest "
                + "on a single answer.\n\n");
            return;
        }
        if (!digest.skipped().isEmpty()) {
            sb.append("Asked on this questionnaire and left unanswered — the client declined or "
                + "skipped these:\n");
            digest.skipped().forEach(k -> sb.append("- ").append(k).append("\n"));
            sb.append("\n");
        }
        if (!digest.terse().isEmpty()) {
            sb.append("Answered in three words or fewer. Some of these are legitimately short (a "
                + "business name, a never-word); for the rest, treat the field they feed as thin "
                + "rather than deriving confidently from them:\n");
            digest.terse().forEach(k -> sb.append("- ").append(k).append("\n"));
            sb.append("\n");
        }
        if (!digest.notCollected().isEmpty()) {
            sb.append("Referenced by the structure module but never asked on this questionnaire. "
                + "This is a gap in the instrument, not in the client — do not write it up as "
                + "something they failed to answer, and do not invent the field it feeds:\n");
            digest.notCollected().forEach(k -> sb.append("- ").append(k).append("\n"));
            sb.append("\n");
        }
    }

    private void appendRevision(StringBuilder sb, PurposePlaybookOutput previous, AgentRevision revision) {
        if (previous == null || revision == null) {
            return;
        }
        sb.append("## Revision request\n");
        sb.append("Creative Director feedback: ").append(revision.feedback()).append("\n");
        if (!revision.specificIssues().isEmpty()) {
            sb.append("Issues to address:\n");
            revision.specificIssues().forEach(i -> sb.append("- ").append(i).append("\n"));
        }
        if (!revision.fieldsToRevise().isEmpty()) {
            sb.append("Sections to revise: ").append(String.join(", ", revision.fieldsToRevise())).append("\n");
        }
        sb.append("\nReturn the whole document, not only the revised sections. Keep the sections "
            + "that were not criticised as they were — the feedback leads with what is carrying "
            + "its weight for exactly that reason.\n\n");
        sb.append("Your previous sections:\n");
        previous.sections().forEach((key, section) ->
            sb.append("- ").append(key).append(": ").append(firstLine(section.body())).append("\n"));
        sb.append("\n");
    }

    private String firstLine(String body) {
        if (body == null) {
            return "(empty)";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180) + "…";
    }

    @SuppressWarnings("unchecked")
    PurposePlaybookOutput parse(String raw, String engagementId, int iteration) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            // Claude occasionally emits raw newlines inside multi-paragraph string values rather
            // than escaping them — and this document is almost entirely multi-paragraph prose.
            Map<String, Object> map = objectMapper.reader()
                .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .readValue(json, Map.class);

            return new PurposePlaybookOutput(
                engagementId,
                sections((Map<String, Object>) map.get("sections")),
                lookTerritory((Map<String, Object>) map.get("lookTerritory")),
                gaps((List<Map<String, Object>>) map.get("confidence")),
                str(map.get("reasoning")),
                iteration
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to parse PurposePlaybookAgent output: " + e.getMessage() + "\nRaw: " + raw, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, PurposePlaybookOutput.Section> sections(Map<String, Object> raw) {
        Map<String, PurposePlaybookOutput.Section> sections = new LinkedHashMap<>();
        if (raw == null) {
            return sections;
        }
        raw.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> section = (Map<String, Object>) map;
                sections.put(key, new PurposePlaybookOutput.Section(
                    key,
                    str(section.get("title")),
                    str(section.get("body")),
                    strings(section.get("sources")),
                    str(section.get("confidence"))
                ));
            } else {
                // A model that returned bare prose for a section instead of the object: keep the
                // prose rather than dropping a written section, and let the guard flag the
                // missing sources.
                sections.put(key, new PurposePlaybookOutput.Section(key, null, str(value), List.of(), null));
            }
        });
        return sections;
    }

    private PurposePlaybookOutput.LookTerritory lookTerritory(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        return new PurposePlaybookOutput.LookTerritory(
            strings(raw.get("summaryWords")),
            str(raw.get("posture")),
            str(raw.get("palette")),
            str(raw.get("typography")),
            str(raw.get("logo")),
            str(raw.get("imagery")),
            strings(raw.get("tensions")),
            str(raw.get("confidence"))
        );
    }

    private List<PurposePlaybookOutput.Gap> gaps(List<Map<String, Object>> raw) {
        List<PurposePlaybookOutput.Gap> gaps = new ArrayList<>();
        if (raw == null) {
            return gaps;
        }
        for (Map<String, Object> row : raw) {
            gaps.add(new PurposePlaybookOutput.Gap(
                str(row.get("field")),
                str(row.get("confidence")),
                str(row.get("whatWouldRaiseIt"))
            ));
        }
        return gaps;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(i -> i != null).map(String::valueOf).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }
}
