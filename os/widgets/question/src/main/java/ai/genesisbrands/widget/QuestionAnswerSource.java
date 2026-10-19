package ai.genesisbrands.widget;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowAnswerSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * A question widget asks one question, configured on the widget itself, and the widget saves
 * the visitor's answer in the session context under its own widget id. The widget id doubles
 * as the transient question id.
 */
@Component
@RequiredArgsConstructor
public class QuestionAnswerSource implements FlowAnswerSource {

    private final ObjectMapper objectMapper;

    @Override
    public String widgetType() {
        return "question";
    }

    @Override
    public FlowAnswers collect(PageWidget widget, Map<String, Object> config, Map<String, Object> context) {
        QuestionnaireQuestion question = new QuestionnaireQuestion();
        question.setId(widget.getId());
        question.setPrompt(String.valueOf(config.getOrDefault("prompt", "")));
        question.setRequired(Boolean.parseBoolean(String.valueOf(config.getOrDefault("required", "false"))));
        question.setType(toQuestionType(String.valueOf(config.getOrDefault("questionType", "short_text"))));

        if (!context.containsKey(widget.getId())) {
            // Skipped by branching — the question still counts, it just has no answer.
            return new FlowAnswers(List.of(question), List.of());
        }
        QuestionnaireAnswer answer = new QuestionnaireAnswer();
        answer.setQuestionId(widget.getId());
        answer.setValueJson(toValueJson(context.get(widget.getId())));
        return new FlowAnswers(List.of(question), List.of(answer));
    }

    private QuestionnaireQuestion.Type toQuestionType(String questionType) {
        return switch (questionType) {
            case "long_text" -> QuestionnaireQuestion.Type.LONG_TEXT;
            case "single_select" -> QuestionnaireQuestion.Type.SINGLE_SELECT;
            case "multi_select" -> QuestionnaireQuestion.Type.MULTI_SELECT;
            default -> QuestionnaireQuestion.Type.SHORT_TEXT;
        };
    }

    private String toValueJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }
}
