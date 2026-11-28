package ai.genesisbrands.widget;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.service.FlowSessionService;
import ai.genesisbrands.service.QuestionnaireResponseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The questionnaire widget's own backend for picking a visitor up where they left off. The widget
 * records the response it started in the flow session context ({@code __questionnaireResponse:<widgetId>});
 * on a reload it asks here whether that response can still be continued, and gets back the id and
 * the answers recorded so far. Everything is read server-side from the session the token names,
 * never from what the request claims.
 *
 * <p>Nothing resumes for an admin preview (it always starts clean), for a response that was already
 * submitted, or for one that belongs to a different questionnaire than the widget shows now.
 */
@RestController
@RequestMapping("/api/public/questionnaire/flow-sessions/{token}/widgets/{widgetId}")
public class QuestionnaireResumeController {

    private final FlowSessionService flowSessions;
    private final QuestionnaireResponseService responses;
    private final ObjectMapper objectMapper;

    public QuestionnaireResumeController(FlowSessionService flowSessions,
                                         QuestionnaireResponseService responses, ObjectMapper objectMapper) {
        this.flowSessions = flowSessions;
        this.responses = responses;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/resume")
    public ResumeView resume(@PathVariable String token, @PathVariable String widgetId,
                             @RequestParam String questionnaireId) {
        FlowSession session;
        try {
            session = flowSessions.get(token);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (session.isSimulated()) return ResumeView.NONE;

        Object saved = context(session).get(QuestionnaireAnswerSource.RESPONSE_KEY_PREFIX + widgetId);
        if (saved == null) return ResumeView.NONE;

        QuestionnaireResponse response;
        try {
            response = responses.getResponse(String.valueOf(saved));
        } catch (NoSuchElementException e) {
            return ResumeView.NONE;
        }
        if (response.getStatus() != QuestionnaireResponse.Status.IN_PROGRESS
            || !questionnaireId.equals(response.getQuestionnaireId())) {
            return ResumeView.NONE;
        }
        List<AnswerView> answers = responses.listAnswers(response.getId()).stream()
            .map(QuestionnaireResumeController::toView).toList();
        return new ResumeView(response.getId(), answers);
    }

    private static AnswerView toView(QuestionnaireAnswer a) {
        return new AnswerView(a.getQuestionId(), a.getValueJson());
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

    public record AnswerView(String questionId, String valueJson) {}

    public record ResumeView(String responseId, List<AnswerView> answers) {
        static final ResumeView NONE = new ResumeView(null, List.of());
    }
}
