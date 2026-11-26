package ai.genesisbrands.widget.logoassessment;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowAnswerSource.FlowAnswers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogoAssessmentAnswerSourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LogoAssessmentAnswerSource source = new LogoAssessmentAnswerSource(objectMapper);

    private static PageWidget widget() {
        PageWidget w = new PageWidget();
        w.setId("w1");
        return w;
    }

    private static Map<String, Object> context(Map<String, Object> record) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("__questionnaireResponse:q1", "r1");
        context.put(LogoAssessmentRecord.KEY_PREFIX + "w1", record);
        return context;
    }

    private static String answerFor(FlowAnswers answers, String signalKey) {
        String id = answers.questions().stream().filter(q -> signalKey.equals(q.getSignalKey()))
            .map(QuestionnaireQuestion::getId).findFirst().orElseThrow();
        return answers.answers().stream().filter(a -> id.equals(a.getQuestionId()))
            .map(QuestionnaireAnswer::getValueJson).findFirst().orElse(null);
    }

    @Test
    void handsOverAssessmentFileAndDecision() throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status", "ASSESSED");
        record.put("logoUrl", "/api/assets/uploads/logo.png");
        record.put("assessment", Map.of("score", 62, "verdict", "EVOLVE"));
        record.put("decision", "keep");

        FlowAnswers answers = source.collect(widget(), Map.of(), context(record));

        assertThat(answers.questions()).extracting(QuestionnaireQuestion::getSignalKey)
            .containsExactly("look.logoAssessment", "look.logoAssessedFile", "look.logoDecision");
        assertThat(objectMapper.readValue(answerFor(answers, "look.logoAssessment"), Map.class))
            .containsEntry("score", 62).containsEntry("verdict", "EVOLVE");
        assertThat(answerFor(answers, "look.logoAssessedFile")).isEqualTo("\"/api/assets/uploads/logo.png\"");
        assertThat(answerFor(answers, "look.logoDecision")).isEqualTo("\"keep\"");
    }

    @Test
    void anUndecidedAssessmentStillAsksTheDecisionQuestionUnanswered() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status", "ASSESSED");
        record.put("logoUrl", "/api/assets/uploads/logo.png");
        record.put("assessment", Map.of("score", 80));

        FlowAnswers answers = source.collect(widget(), Map.of(), context(record));

        assertThat(answers.questions()).hasSize(3);
        assertThat(answers.answers()).hasSize(2);
        assertThat(answerFor(answers, "look.logoDecision")).isNull();
    }

    @Test
    void handsOverNothingWithoutAnAssessment() {
        assertThat(source.collect(widget(), Map.of(), Map.of())).isSameAs(FlowAnswers.NONE);
        assertThat(source.collect(widget(), Map.of(), context(Map.of("status", "NO_LOGO"))))
            .isSameAs(FlowAnswers.NONE);
        assertThat(source.collect(widget(), Map.of(),
            context(Map.of("status", "UNVIEWABLE", "logoUrl", "/api/assets/x.ai")))).isSameAs(FlowAnswers.NONE);
    }

    @Test
    void ignoresAnotherWidgetsRecord() {
        Map<String, Object> context = Map.of(LogoAssessmentRecord.KEY_PREFIX + "other",
            Map.of("status", "ASSESSED", "assessment", Map.of("score", 1)));
        assertThat(source.collect(widget(), Map.of(), context)).isSameAs(FlowAnswers.NONE);
    }
}
