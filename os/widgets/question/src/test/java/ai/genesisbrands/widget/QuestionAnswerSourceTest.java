package ai.genesisbrands.widget;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowAnswerSource.FlowAnswers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionAnswerSourceTest {

    private final QuestionAnswerSource source = new QuestionAnswerSource(new ObjectMapper());

    private PageWidget widget(String id) {
        PageWidget w = new PageWidget();
        w.setId(id);
        w.setWidgetType("question");
        return w;
    }

    @Test
    void collect_buildsQuestionFromConfig_andAnswerFromContext() {
        FlowAnswers a = source.collect(widget("w1"),
            Map.of("prompt", "Your name?", "required", true, "questionType", "short_text"),
            Map.of("w1", "Ada"));

        QuestionnaireQuestion q = a.questions().get(0);
        assertThat(q.getId()).isEqualTo("w1");
        assertThat(q.getPrompt()).isEqualTo("Your name?");
        assertThat(q.isRequired()).isTrue();
        assertThat(q.getType()).isEqualTo(QuestionnaireQuestion.Type.SHORT_TEXT);
        assertThat(a.answers()).extracting(QuestionnaireAnswer::getValueJson).containsExactly("\"Ada\"");
    }

    @Test
    void collect_multiSelect_serialisesTheListAnswer() {
        FlowAnswers a = source.collect(widget("w2"),
            Map.of("prompt", "Pick colours", "questionType", "multi_select"),
            Map.of("w2", List.of("Red", "Blue")));

        assertThat(a.questions().get(0).getType()).isEqualTo(QuestionnaireQuestion.Type.MULTI_SELECT);
        assertThat(a.answers().get(0).getValueJson()).isEqualTo("[\"Red\",\"Blue\"]");
    }

    @Test
    void collect_unansweredBranchSkippedQuestion_keepsQuestion_noAnswer() {
        FlowAnswers a = source.collect(widget("w3"), Map.of("prompt", "Never reached"), Map.of());

        assertThat(a.questions()).extracting(QuestionnaireQuestion::getId).containsExactly("w3");
        assertThat(a.answers()).isEmpty();
    }
}
