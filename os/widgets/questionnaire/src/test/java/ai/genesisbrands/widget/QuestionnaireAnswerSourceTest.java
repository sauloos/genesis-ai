package ai.genesisbrands.widget;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowAnswerSource.FlowAnswers;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionnaireAnswerSourceTest {

    @Mock private QuestionnaireQuestionRepository questionRepo;
    @Mock private QuestionnaireAnswerRepository answerRepo;
    @InjectMocks private QuestionnaireAnswerSource source;

    private QuestionnaireQuestion question(String id, String signalKey, String condition) {
        QuestionnaireQuestion q = new QuestionnaireQuestion();
        q.setId(id);
        q.setSignalKey(signalKey);
        q.setConditionExpr(condition);
        return q;
    }

    private PageWidget widget(String id) {
        PageWidget w = new PageWidget();
        w.setId(id);
        w.setWidgetType("questionnaire");
        return w;
    }

    @Test
    void collect_readsTheSubmittedResponse_withTheQuestionnairesQuestions() {
        QuestionnaireQuestion q = new QuestionnaireQuestion();
        q.setId("rq1");
        QuestionnaireAnswer a = new QuestionnaireAnswer();
        a.setQuestionId("rq1");
        when(questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc("q1")).thenReturn(List.of(q));
        when(answerRepo.findByResponseIdOrderByCreatedAtAsc("r1")).thenReturn(List.of(a));

        FlowAnswers collected = source.collect(widget("w2"), Map.of("questionnaireId", "q1"),
            Map.of("__questionnaireResponse:w2", "r1"));

        assertThat(collected.questions()).containsExactly(q);
        assertThat(collected.answers()).containsExactly(a);
    }

    @Test
    void collect_leavesOutTheQuestionsAConditionGatedAway() {
        // Downstream, a question with no answer means "asked and declined", which a Purpose
        // Playbook reports as a gap and hedges around. A question the visitor was never shown
        // must not arrive looking like one, or a complete response reads as a thin one.
        QuestionnaireQuestion asked = question("rq1", "belong.segmentCount", null);
        QuestionnaireQuestion gated = question("rq2", "belong.persona[1].story",
            "belong.segmentCount != '1'");
        QuestionnaireAnswer answer = new QuestionnaireAnswer();
        answer.setQuestionId("rq1");
        answer.setValueJson("\"1\"");
        when(questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc("q1"))
            .thenReturn(List.of(asked, gated));
        when(answerRepo.findByResponseIdOrderByCreatedAtAsc("r1")).thenReturn(List.of(answer));

        FlowAnswers collected = source.collect(widget("w2"), Map.of("questionnaireId", "q1"),
            Map.of("__questionnaireResponse:w2", "r1"));

        assertThat(collected.questions()).containsExactly(asked);
        assertThat(collected.answers()).containsExactly(answer);
    }

    @Test
    void collect_noResponseIdInContext_isEmpty() {
        FlowAnswers collected = source.collect(widget("w2"), Map.of("questionnaireId", "q1"), Map.of());

        assertThat(collected).isEqualTo(FlowAnswers.NONE);
        verifyNoInteractions(questionRepo, answerRepo);
    }
}
