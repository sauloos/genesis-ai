package ai.genesisbrands.widget.logoassessment;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.service.FlowSessionService;
import ai.genesisbrands.service.QuestionnaireResponseService;
import ai.genesisbrands.service.QuestionnaireService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogoAssessmentControllerTest {

    @Mock private FlowSessionService flowSessions;
    @Mock private QuestionnaireService questionnaires;
    @Mock private QuestionnaireResponseService responses;
    @Mock private ClientLogoAssessor assessor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LogoAssessmentController controller;

    @BeforeEach
    void setUp() {
        controller = new LogoAssessmentController(flowSessions, questionnaires, responses, assessor, objectMapper);
    }

    private FlowSession session(String contextJson, boolean simulated) {
        FlowSession s = new FlowSession();
        s.setToken("t1");
        s.setContextJson(contextJson);
        s.setSimulated(simulated);
        when(flowSessions.get("t1")).thenReturn(s);
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedPatch() throws Exception {
        ArgumentCaptor<String> patch = ArgumentCaptor.forClass(String.class);
        verify(flowSessions).updateContext(any(), patch.capture());
        return (Map<String, Object>) objectMapper.readValue(patch.getValue(), Map.class);
    }

    @Test
    void assessesFromTheSessionsOwnAnswersAndStoresTheResult() throws Exception {
        session("{\"__questionnaireResponse:q\":\"r1\"}", false);
        QuestionnaireResponse response = new QuestionnaireResponse();
        response.setId("r1");
        response.setQuestionnaireId("qn");
        when(responses.getResponse("r1")).thenReturn(response);
        QuestionnaireQuestion logo = new QuestionnaireQuestion();
        logo.setId("q-logo");
        logo.setSignalKey("look.currentLogo");
        QuestionnaireQuestion intent = new QuestionnaireQuestion();
        intent.setId("q-intent");
        intent.setSignalKey("look.logoIntent");
        when(questionnaires.listActiveQuestions("qn", "r1")).thenReturn(List.of(logo, intent));
        QuestionnaireAnswer logoAnswer = new QuestionnaireAnswer();
        logoAnswer.setQuestionId("q-logo");
        logoAnswer.setValueJson("[{\"blobPath\":\"/api/assets/uploads/a.png\"}]");
        QuestionnaireAnswer intentAnswer = new QuestionnaireAnswer();
        intentAnswer.setQuestionId("q-intent");
        intentAnswer.setValueJson("\"evolve it\"");
        when(responses.listAnswers("r1")).thenReturn(List.of(logoAnswer, intentAnswer));
        when(assessor.assess(any())).thenReturn(new ClientLogoAssessor.Result(
            ClientLogoAssessor.Status.ASSESSED, "/api/assets/uploads/a.png", Map.of("score", 70)));

        Map<String, Object> record = controller.assess("t1", "w1");

        assertThat(record).containsEntry("status", "ASSESSED")
            .containsEntry("logoUrl", "/api/assets/uploads/a.png")
            .containsEntry("intent", "evolve it");
        ArgumentCaptor<Map<String, Object>> signals = ArgumentCaptor.forClass(Map.class);
        verify(assessor).assess(signals.capture());
        assertThat(signals.getValue()).containsKeys("look.currentLogo", "look.logoIntent");
        assertThat(savedPatch()).containsKey(LogoAssessmentRecord.KEY_PREFIX + "w1");
    }

    @Test
    void returnsTheStoredResultWithoutAssessingAgain() {
        session("{\"__logoAssessment:w1\":{\"status\":\"ASSESSED\",\"assessment\":{\"score\":70}}}", false);

        assertThat(controller.assess("t1", "w1")).containsEntry("status", "ASSESSED");
        verify(assessor, never()).assess(any());
    }

    @Test
    void neverAssessesInASimulatedSession() {
        session("{}", true);

        assertThatThrownBy(() -> controller.assess("t1", "w1"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(assessor, never()).assess(any());
    }

    @Test
    void anUnknownSessionIsNotFound() {
        when(flowSessions.get("nope")).thenThrow(new NoSuchElementException("no session"));

        assertThatThrownBy(() -> controller.assess("nope", "w1"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void recordsADecisionOnAnAssessedLogo() throws Exception {
        session("{\"__logoAssessment:w1\":{\"status\":\"ASSESSED\",\"assessment\":{\"score\":70}}}", false);

        Map<String, Object> record = controller.decide("t1", "w1", Map.of("decision", "keep"));

        assertThat(record).containsEntry("decision", "keep").containsEntry("status", "ASSESSED");
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) savedPatch().get(LogoAssessmentRecord.KEY_PREFIX + "w1");
        assertThat(saved)
            .containsEntry("decision", "keep");
    }

    @Test
    void refusesAnUnknownDecisionOrOneWithNothingAssessed() {
        assertThatThrownBy(() -> controller.decide("t1", "w1", Map.of("decision", "burn it")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");

        session("{\"__logoAssessment:w1\":{\"status\":\"NO_LOGO\"}}", false);
        assertThatThrownBy(() -> controller.decide("t1", "w1", Map.of("decision", "keep")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(flowSessions, never()).updateContext(anyString(), anyString());
    }
}
