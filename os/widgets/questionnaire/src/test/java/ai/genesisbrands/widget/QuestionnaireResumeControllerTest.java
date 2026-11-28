package ai.genesisbrands.widget;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.service.FlowSessionService;
import ai.genesisbrands.service.QuestionnaireResponseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionnaireResumeControllerTest {

    @Mock private FlowSessionService flowSessions;
    @Mock private QuestionnaireResponseService responses;
    private QuestionnaireResumeController controller;

    @BeforeEach
    void setUp() {
        controller = new QuestionnaireResumeController(flowSessions, responses, new ObjectMapper());
    }

    private FlowSession session(String contextJson, boolean simulated) {
        FlowSession s = new FlowSession();
        s.setContextJson(contextJson);
        s.setSimulated(simulated);
        return s;
    }

    private QuestionnaireResponse response(String id, String questionnaireId, QuestionnaireResponse.Status status) {
        QuestionnaireResponse r = new QuestionnaireResponse();
        r.setId(id);
        r.setQuestionnaireId(questionnaireId);
        r.setStatus(status);
        return r;
    }

    @Test
    void inProgressResponseResumesWithItsAnswers() {
        when(flowSessions.get("t")).thenReturn(session("{\"__questionnaireResponse:w1\":\"r1\"}", false));
        when(responses.getResponse("r1")).thenReturn(response("r1", "q1", QuestionnaireResponse.Status.IN_PROGRESS));
        QuestionnaireAnswer a = new QuestionnaireAnswer();
        a.setQuestionId("qq1");
        a.setValueJson("\"hello\"");
        when(responses.listAnswers("r1")).thenReturn(List.of(a));

        var view = controller.resume("t", "w1", "q1");

        assertThat(view.responseId()).isEqualTo("r1");
        assertThat(view.answers()).hasSize(1);
        assertThat(view.answers().get(0).valueJson()).isEqualTo("\"hello\"");
    }

    @Test
    void noSavedResponseStartsFresh() {
        when(flowSessions.get("t")).thenReturn(session("{}", false));
        assertThat(controller.resume("t", "w1", "q1").responseId()).isNull();
    }

    @Test
    void submittedResponseIsNotResumed() {
        when(flowSessions.get("t")).thenReturn(session("{\"__questionnaireResponse:w1\":\"r1\"}", false));
        when(responses.getResponse("r1")).thenReturn(response("r1", "q1", QuestionnaireResponse.Status.SUBMITTED));
        assertThat(controller.resume("t", "w1", "q1").responseId()).isNull();
    }

    @Test
    void responseOfAnotherQuestionnaireIsNotResumed() {
        when(flowSessions.get("t")).thenReturn(session("{\"__questionnaireResponse:w1\":\"r1\"}", false));
        when(responses.getResponse("r1")).thenReturn(response("r1", "other", QuestionnaireResponse.Status.IN_PROGRESS));
        assertThat(controller.resume("t", "w1", "q1").responseId()).isNull();
    }

    @Test
    void missingResponseStartsFresh() {
        when(flowSessions.get("t")).thenReturn(session("{\"__questionnaireResponse:w1\":\"gone\"}", false));
        when(responses.getResponse("gone")).thenThrow(new NoSuchElementException("gone"));
        assertThat(controller.resume("t", "w1", "q1").responseId()).isNull();
    }

    @Test
    void simulatedSessionAlwaysStartsFresh() {
        when(flowSessions.get("t")).thenReturn(session("{\"__questionnaireResponse:w1\":\"r1\"}", true));
        assertThat(controller.resume("t", "w1", "q1").responseId()).isNull();
        verifyNoInteractions(responses);
    }

    @Test
    void unknownSessionIs404() {
        when(flowSessions.get("t")).thenThrow(new NoSuchElementException("no session"));
        assertThatThrownBy(() -> controller.resume("t", "w1", "q1")).isInstanceOf(ResponseStatusException.class);
    }
}
