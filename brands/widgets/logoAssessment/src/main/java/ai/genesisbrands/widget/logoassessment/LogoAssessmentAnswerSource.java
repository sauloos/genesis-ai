package ai.genesisbrands.widget.logoassessment;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowAnswerSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hands the engagement what the logoAssessment widget learned, as two answers under Layer 1
 * signal keys:
 * <ul>
 *   <li>{@code look.logoAssessment}: the assessment itself (score, verdict, what to keep and
 *       change), which the Purpose Playbook reads and the logo step builds on;</li>
 *   <li>{@code look.logoDecision}: keep / evolve / fresh, chosen after reading it. {@code keep}
 *       makes the client's own logo the ANCHORED direction's logo.</li>
 *   <li>{@code look.logoAssessedFile}: which of their uploads was assessed, so a kept logo is
 *       that exact file.</li>
 * </ul>
 * Nothing is handed over when the widget assessed nothing (no logo, or a file it cannot read).
 */
@Component
public class LogoAssessmentAnswerSource implements FlowAnswerSource {

    static final String ASSESSMENT_SIGNAL = "look.logoAssessment";
    static final String DECISION_SIGNAL = "look.logoDecision";
    static final String FILE_SIGNAL = "look.logoAssessedFile";

    private final ObjectMapper objectMapper;

    public LogoAssessmentAnswerSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String widgetType() {
        return LogoAssessmentWidget.TYPE;
    }

    @Override
    public FlowAnswers collect(PageWidget widget, Map<String, Object> config, Map<String, Object> context) {
        Map<String, Object> record = LogoAssessmentRecord.read(context, widget.getId());
        if (record == null || !ClientLogoAssessor.Status.ASSESSED.name().equals(record.get("status"))
            || record.get("assessment") == null) {
            return FlowAnswers.NONE;
        }
        List<QuestionnaireQuestion> questions = new ArrayList<>();
        List<QuestionnaireAnswer> answers = new ArrayList<>();
        add(questions, answers, widget.getId() + ":assessment", ASSESSMENT_SIGNAL,
            "Our assessment of the client's current logo", QuestionnaireQuestion.Type.LONG_TEXT,
            record.get("assessment"));
        add(questions, answers, widget.getId() + ":file", FILE_SIGNAL,
            "The logo file we assessed", QuestionnaireQuestion.Type.SHORT_TEXT, record.get("logoUrl"));
        // Asked whether or not they answered: a client who read the assessment and chose nothing
        // left the decision open, which is itself worth knowing.
        add(questions, answers, widget.getId() + ":decision", DECISION_SIGNAL,
            "Having read our assessment, what should we do with your current logo? (keep / evolve / fresh)",
            QuestionnaireQuestion.Type.SINGLE_SELECT, record.get("decision"));
        return new FlowAnswers(questions, answers);
    }

    private void add(List<QuestionnaireQuestion> questions, List<QuestionnaireAnswer> answers,
                     String id, String signalKey, String prompt, QuestionnaireQuestion.Type type, Object value) {
        QuestionnaireQuestion q = new QuestionnaireQuestion();
        q.setId(id);
        q.setSignalKey(signalKey);
        q.setPrompt(prompt);
        q.setType(type);
        questions.add(q);
        if (value == null) return;
        QuestionnaireAnswer a = new QuestionnaireAnswer();
        a.setQuestionId(id);
        try {
            a.setValueJson(objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            return;
        }
        answers.add(a);
    }
}
