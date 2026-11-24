package ai.genesisbrands.widget;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowAnswerSource;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import ai.genesisbrands.service.QuestionnaireSignals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * A questionnaire widget stores its answers as a real questionnaire response and records the
 * response id in the session context under {@code __questionnaireResponse:<widgetId>} (see
 * widget.js). This reads that response back with the questions it actually asked.
 *
 * <p><b>Actually asked</b> is the whole subtlety, and it is why {@link QuestionnaireSignals#active}
 * is applied here rather than every question being handed over. What a downstream agent infers
 * from a question with no answer is that the client was asked and declined, which is real signal —
 * a Purpose Playbook names it as a gap and hedges every field that rests on it. A question a
 * condition gated away was never put to anyone: on an instrument with real branching (three
 * persona blocks, five competitor slots, seven vision-board follow-ups) handing over the full set
 * would manufacture a dozen skipped questions out of a complete response, and the symptom would
 * be a playbook that reads as thin about a client who answered everything they were shown.
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
        List<QuestionnaireAnswer> answers =
            answerRepo.findByResponseIdOrderByCreatedAtAsc(String.valueOf(responseId));
        List<QuestionnaireQuestion> asked = QuestionnaireSignals.active(
            questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(questionnaireId), answers);
        return new FlowAnswers(asked, answers);
    }
}
