package ai.genesisbrands.widget.logoassessment;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.service.FlowSessionService;
import ai.genesisbrands.service.QuestionnaireResponseService;
import ai.genesisbrands.service.QuestionnaireService;
import ai.genesisbrands.service.QuestionnaireSignals;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The logoAssessment widget's backend. {@code assess} runs the audit once per session and widget
 * and stores the result in the session context; asking again (a reload, a second tab) returns the
 * stored result, and a request that arrives while the first is still running waits for it instead
 * of paying for a second vision call. {@code decision} records keep / evolve / fresh.
 *
 * <p>The logo and every other answer are read server-side from the questionnaire responses this
 * session recorded, never from the request, so the assessment is always of what the client
 * actually uploaded.
 */
@RestController
@RequestMapping("/api/public/logo-assessment/flow-sessions/{token}/widgets/{widgetId}")
public class LogoAssessmentController {

    private static final Logger log = LoggerFactory.getLogger(LogoAssessmentController.class);
    static final Set<String> DECISIONS = Set.of("keep", "evolve", "fresh");

    private final FlowSessionService flowSessions;
    private final QuestionnaireService questionnaires;
    private final QuestionnaireResponseService responses;
    private final ClientLogoAssessor assessor;
    private final ObjectMapper objectMapper;
    private final Map<String, CompletableFuture<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();

    public LogoAssessmentController(FlowSessionService flowSessions, QuestionnaireService questionnaires,
                                    QuestionnaireResponseService responses, ClientLogoAssessor assessor,
                                    ObjectMapper objectMapper) {
        this.flowSessions = flowSessions;
        this.questionnaires = questionnaires;
        this.responses = responses;
        this.assessor = assessor;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/assess")
    public Map<String, Object> assess(@PathVariable String token, @PathVariable String widgetId) {
        FlowSession session = session(token);
        Map<String, Object> stored = LogoAssessmentRecord.read(context(session), widgetId);
        if (stored != null) return stored;
        if (session.isSimulated()) {
            // An admin preview never spends a vision call; the widget shows its sample instead.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulated sessions do not run the logo assessment");
        }

        CompletableFuture<Map<String, Object>> mine = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> running = inFlight.putIfAbsent(token + "/" + widgetId, mine);
        if (running != null) return join(running);
        try {
            Map<String, Object> record = run(session, widgetId);
            mine.complete(record);
            return record;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(token + "/" + widgetId);
        }
    }

    @PostMapping("/decision")
    public Map<String, Object> decide(@PathVariable String token, @PathVariable String widgetId,
                                      @RequestBody Map<String, String> body) {
        String decision = body == null ? null : body.get("decision");
        if (decision == null || !DECISIONS.contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be keep, evolve or fresh");
        }
        FlowSession session = session(token);
        Map<String, Object> stored = LogoAssessmentRecord.read(context(session), widgetId);
        if (stored == null || !ClientLogoAssessor.Status.ASSESSED.name().equals(stored.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no assessed logo to decide about");
        }
        Map<String, Object> record = new LinkedHashMap<>(stored);
        record.put("decision", decision);
        save(token, widgetId, record);
        return record;
    }

    private Map<String, Object> run(FlowSession session, String widgetId) {
        Map<String, Object> signals = signals(context(session));
        ClientLogoAssessor.Result result = assessor.assess(signals);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status", result.status().name());
        if (result.logoUrl() != null) record.put("logoUrl", result.logoUrl());
        if (result.assessment() != null) record.put("assessment", result.assessment());
        Object intent = signals.get("look.logoIntent");
        if (intent != null) record.put("intent", String.valueOf(intent));
        save(session.getToken(), widgetId, record);
        log.info("Logo assessment for flow session {} widget {}: {}", session.getToken(), widgetId, result.status());
        return record;
    }

    /** Every answer this session's questionnaires recorded, keyed by signal key. */
    private Map<String, Object> signals(Map<String, Object> context) {
        Map<String, Object> signals = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : context.entrySet()) {
            if (!e.getKey().startsWith(LogoAssessmentRecord.RESPONSE_KEY_PREFIX) || e.getValue() == null) continue;
            String responseId = String.valueOf(e.getValue());
            try {
                QuestionnaireResponse response = responses.getResponse(responseId);
                signals.putAll(QuestionnaireSignals.collect(
                    questionnaires.listActiveQuestions(response.getQuestionnaireId(), responseId),
                    responses.listAnswers(responseId)));
            } catch (NoSuchElementException ex) {
                log.warn("Flow session context names questionnaire response {}, which does not exist", responseId);
            }
        }
        return signals;
    }

    private void save(String token, String widgetId, Map<String, Object> record) {
        try {
            flowSessions.updateContext(token, objectMapper.writeValueAsString(
                Map.of(LogoAssessmentRecord.KEY_PREFIX + widgetId, record)));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the logo assessment");
        }
    }

    private FlowSession session(String token) {
        try {
            return flowSessions.get(token);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private Map<String, Object> context(FlowSession session) {
        String json = session.getContextJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, Object> join(CompletableFuture<Map<String, Object>> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }
}
