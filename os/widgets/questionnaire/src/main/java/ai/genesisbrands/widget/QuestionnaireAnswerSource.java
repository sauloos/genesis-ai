package ai.genesisbrands.widget;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.FlowAnswerSource;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A questionnaire widget stores its answers as a real questionnaire response and records the
 * response id in the session context under {@code __questionnaireResponse:<widgetId>} (see
 * widget.js). This reads that response back with the questionnaire's own questions.
 */
@Component
@RequiredArgsConstructor
public class QuestionnaireAnswerSource implements FlowAnswerSource {

    static final String RESPONSE_KEY_PREFIX = "__questionnaireResponse:";

    private final QuestionnaireQuestionRepository questionRepo;
    private final QuestionnaireAnswerRepository answerRepo;

    @Override
    public String widgetType() {
        return "questionnaire";
    }

    @Override
    public FlowAnswers collect(PageWidget widget, Map<String, Object> config, Map<String, Object> context) {
        Object responseId = context.get(RESPONSE_KEY_PREFIX + widget.getId());
        if (responseId == null) {
            return FlowAnswers.NONE; // visitor never reached/completed this branch
        }
        String questionnaireId = String.valueOf(config.getOrDefault("questionnaireId", ""));
        return new FlowAnswers(
            questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(questionnaireId),
            answerRepo.findByResponseIdOrderByCreatedAtAsc(String.valueOf(responseId)));
    }
}
