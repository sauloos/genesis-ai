package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowEngagementTrigger;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowEngagementServiceTest {

    @Mock private PageRepository pageRepo;
    @Mock private PageWidgetRepository pageWidgetRepo;
    @Mock private QuestionnaireQuestionRepository questionnaireQuestionRepo;
    @Mock private QuestionnaireAnswerRepository questionnaireAnswerRepo;
    @Mock private FlowEngagementTrigger flowEngagementTrigger;

    private FlowEngagementService service;

    @BeforeEach
    void setUp() {
        service = new FlowEngagementService(pageRepo, pageWidgetRepo, questionnaireQuestionRepo,
            questionnaireAnswerRepo, Optional.of(flowEngagementTrigger), new ObjectMapper());
    }

    private PageFlow flow(String id) {
        PageFlow f = new PageFlow();
        f.setId(id);
        return f;
    }

    private Page page(String id, String flowId) {
        Page p = new Page();
        p.setId(id);
        p.setPageFlowId(flowId);
        return p;
    }

    private PageWidget widget(String id, String pageId, String widgetType, String configJson) {
        PageWidget w = new PageWidget();
        w.setId(id);
        w.setPageId(pageId);
        w.setWidgetType(widgetType);
        w.setConfigJson(configJson);
        return w;
    }

    private FlowSession session(String flowId, String contextJson) {
        FlowSession s = new FlowSession();
        s.setToken("tok");
        s.setPageFlowId(flowId);
        s.setContextJson(contextJson);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return s;
    }

    @Test
    void triggerEngagement_buildsTransientQuestionsAndAnswers_andCallsTrigger() {
        PageFlow f = flow("f1");
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of(page("p1", "f1")));
        when(pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(List.of("p1"))).thenReturn(List.of(
            widget("w1", "p1", "question", "{\"prompt\":\"Your name?\",\"required\":true,\"questionType\":\"short_text\"}"),
            widget("w2", "p1", "question", "{\"prompt\":\"Pick colours\",\"required\":false,\"questionType\":\"multi_select\"}"),
            widget("w3", "p1", "content", "{\"heading\":\"Hi\"}")
        ));
        FlowSession s = session("f1", "{\"w1\":\"Ada\",\"w2\":[\"Red\",\"Blue\"]}");
        when(flowEngagementTrigger.createAndRun(anyList(), anyList(), isNull())).thenReturn("eng-1");

        String result = service.triggerEngagement(f, s);

        assertThat(result).isEqualTo("eng-1");
        ArgumentCaptor<List<QuestionnaireQuestion>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<QuestionnaireAnswer>> answersCaptor = ArgumentCaptor.forClass(List.class);
        verify(flowEngagementTrigger).createAndRun(questionsCaptor.capture(), answersCaptor.capture(), isNull());

        List<QuestionnaireQuestion> questions = questionsCaptor.getValue();
        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).getId()).isEqualTo("w1");
        assertThat(questions.get(0).getPrompt()).isEqualTo("Your name?");
        assertThat(questions.get(0).isRequired()).isTrue();
        assertThat(questions.get(0).getType()).isEqualTo(QuestionnaireQuestion.Type.SHORT_TEXT);
        assertThat(questions.get(1).getType()).isEqualTo(QuestionnaireQuestion.Type.MULTI_SELECT);

        List<QuestionnaireAnswer> answers = answersCaptor.getValue();
        assertThat(answers).hasSize(2);
        assertThat(answers).extracting(QuestionnaireAnswer::getQuestionId).containsExactly("w1", "w2");
        assertThat(answers.get(0).getValueJson()).isEqualTo("\"Ada\"");
        assertThat(answers.get(1).getValueJson()).isEqualTo("[\"Red\",\"Blue\"]");
    }

    @Test
    void triggerEngagement_missingAnswerForBranchSkippedWidget_omitsIt_doesNotThrow() {
        PageFlow f = flow("f1");
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of(page("p1", "f1")));
        when(pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(List.of("p1"))).thenReturn(List.of(
            widget("w1", "p1", "question", "{\"prompt\":\"Reached\",\"questionType\":\"short_text\"}"),
            widget("w2", "p1", "question", "{\"prompt\":\"Never reached\",\"questionType\":\"short_text\"}")
        ));
        FlowSession s = session("f1", "{\"w1\":\"answered\"}");
        when(flowEngagementTrigger.createAndRun(anyList(), anyList(), isNull())).thenReturn("eng-2");

        service.triggerEngagement(f, s);

        ArgumentCaptor<List<QuestionnaireAnswer>> answersCaptor = ArgumentCaptor.forClass(List.class);
        verify(flowEngagementTrigger).createAndRun(anyList(), answersCaptor.capture(), isNull());
        assertThat(answersCaptor.getValue()).extracting(QuestionnaireAnswer::getQuestionId).containsExactly("w1");
    }

    @Test
    void triggerEngagement_questionnaireWidget_mergesRealQuestionsAndAnswers_alongsideQuestionWidget() {
        PageFlow f = flow("f1");
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of(page("p1", "f1")));
        when(pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(List.of("p1"))).thenReturn(List.of(
            widget("w1", "p1", "question", "{\"prompt\":\"Your name?\",\"required\":true,\"questionType\":\"short_text\"}"),
            widget("w2", "p1", "questionnaire", "{\"questionnaireId\":\"q1\"}")
        ));
        FlowSession s = session("f1", "{\"w1\":\"Ada\",\"__questionnaireResponse:w2\":\"r1\"}");

        QuestionnaireQuestion realQuestion = new QuestionnaireQuestion();
        realQuestion.setId("rq1");
        realQuestion.setQuestionnaireId("q1");
        realQuestion.setPrompt("Favourite colour?");
        when(questionnaireQuestionRepo.findByQuestionnaireIdOrderByOrderIndexAsc("q1")).thenReturn(List.of(realQuestion));

        QuestionnaireAnswer realAnswer = new QuestionnaireAnswer();
        realAnswer.setQuestionId("rq1");
        realAnswer.setValueJson("\"Blue\"");
        when(questionnaireAnswerRepo.findByResponseIdOrderByCreatedAtAsc("r1")).thenReturn(List.of(realAnswer));

        when(flowEngagementTrigger.createAndRun(anyList(), anyList(), isNull())).thenReturn("eng-3");

        String result = service.triggerEngagement(f, s);

        assertThat(result).isEqualTo("eng-3");
        ArgumentCaptor<List<QuestionnaireQuestion>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<QuestionnaireAnswer>> answersCaptor2 = ArgumentCaptor.forClass(List.class);
        verify(flowEngagementTrigger).createAndRun(questionsCaptor.capture(), answersCaptor2.capture(), isNull());

        assertThat(questionsCaptor.getValue()).extracting(QuestionnaireQuestion::getId).containsExactly("w1", "rq1");
        assertThat(answersCaptor2.getValue()).extracting(QuestionnaireAnswer::getQuestionId).containsExactly("w1", "rq1");
    }

    @Test
    void triggerEngagement_questionnaireWidget_noResponseIdInContext_isSkipped_doesNotThrow() {
        PageFlow f = flow("f1");
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of(page("p1", "f1")));
        when(pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(List.of("p1"))).thenReturn(List.of(
            widget("w2", "p1", "questionnaire", "{\"questionnaireId\":\"q1\"}")
        ));
        FlowSession s = session("f1", "{}");
        when(flowEngagementTrigger.createAndRun(anyList(), anyList(), isNull())).thenReturn("eng-4");

        service.triggerEngagement(f, s);

        ArgumentCaptor<List<QuestionnaireQuestion>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(flowEngagementTrigger).createAndRun(questionsCaptor.capture(), anyList(), isNull());
        assertThat(questionsCaptor.getValue()).isEmpty();
    }
}
