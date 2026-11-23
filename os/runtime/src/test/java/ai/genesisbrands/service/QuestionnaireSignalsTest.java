package ai.genesisbrands.service;

import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signal map is the contract between the questionnaire and everything downstream, so the
 * properties worth pinning are: a question's wording and id never leak into it, an answer
 * keeps the JSON shape it was saved with, and "answered" is decidable by key presence alone.
 */
class QuestionnaireSignalsTest {

    private final List<QuestionnaireQuestion> questions = new ArrayList<>();
    private final List<QuestionnaireAnswer> answers = new ArrayList<>();

    private QuestionnaireQuestion question(String id, String signalKey) {
        return question(id, signalKey, null);
    }

    private QuestionnaireQuestion question(String id, String signalKey, String conditionExpr) {
        var q = new QuestionnaireQuestion();
        q.setId(id);
        q.setQuestionnaireId("qn");
        q.setType(QuestionnaireQuestion.Type.SHORT_TEXT);
        q.setPrompt("prompt " + id);
        q.setSignalKey(signalKey);
        q.setConditionExpr(conditionExpr);
        q.setOrderIndex(questions.size());
        questions.add(q);
        return q;
    }

    private void answer(String questionId, String valueJson) {
        var a = new QuestionnaireAnswer();
        a.setId("a-" + questionId);
        a.setResponseId("r");
        a.setQuestionId(questionId);
        a.setValueJson(valueJson);
        answers.add(a);
    }

    @Test
    @DisplayName("answers are keyed by signal key, in question order")
    void keysBySignal() {
        question("q1", "setup.businessName");
        question("q2", "belief.origin");
        answer("q1", "\"Cre8ion\"");
        answer("q2", "\"A client walked away\"");

        assertThat(QuestionnaireSignals.collect(questions, answers))
            .containsExactly(
                java.util.Map.entry("setup.businessName", "Cre8ion"),
                java.util.Map.entry("belief.origin", "A client walked away"));
    }

    @Test
    @DisplayName("an answer keeps the JSON shape it was saved with")
    void keepsShape() {
        question("q1", "beyond.sdgs");
        question("q2", "look.slider.era");
        question("q3", "look.currentLogo");
        answer("q1", "[\"climate-action\",\"good-health\"]");
        answer("q2", "4");
        answer("q3", "{\"blobPath\":\"/api/assets/logo.png\"}");

        var signals = QuestionnaireSignals.collect(questions, answers);
        assertThat(signals.get("beyond.sdgs")).isEqualTo(List.of("climate-action", "good-health"));
        assertThat(signals.get("look.slider.era")).isEqualTo(4);
        assertThat(signals.get("look.currentLogo")).isEqualTo(java.util.Map.of("blobPath", "/api/assets/logo.png"));
    }

    @Test
    @DisplayName("a question with no signal key contributes nothing")
    void skipsUnkeyedQuestions() {
        question("q1", null);
        answer("q1", "\"something\"");

        assertThat(QuestionnaireSignals.collect(questions, answers)).isEmpty();
    }

    @Test
    @DisplayName("an unanswered or empty answer leaves the key out, so containsKey means answered")
    void absenceIsAbsence() {
        question("q1", "belief.origin");
        question("q2", "belief.loss");
        question("q3", "belief.now");
        answer("q2", "\"   \"");
        answer("q3", "[]");

        assertThat(QuestionnaireSignals.collect(questions, answers)).isEmpty();
    }

    @Test
    @DisplayName("a legacy answer saved as a bare, unquoted string still reads")
    void toleratesBareStrings() {
        question("q1", "belief.origin");
        answer("q1", "not valid json");

        assertThat(QuestionnaireSignals.collect(questions, answers))
            .containsEntry("belief.origin", "not valid json");
    }

    @Test
    @DisplayName("active() drops the questions whose condition does not hold")
    void filtersByCondition() {
        question("q1", "belong.buyerIsUser");
        question("q2", "belong.buyer.role", "belong.buyerIsUser == 'no'");
        question("q3", "belong.buyer.risk", "belong.buyerIsUser == 'yes'");
        question("q4", "belief.origin");
        answer("q1", "\"no\"");

        assertThat(QuestionnaireSignals.active(questions, answers))
            .extracting(QuestionnaireQuestion::getId)
            .containsExactly("q1", "q2", "q4");
    }

    @Test
    @DisplayName("conditions see only the answers given so far, not later ones")
    void conditionsSeeAnswersSoFar() {
        question("q1", "belong.buyerIsUser");
        question("q2", "belong.buyer.role", "belong.buyerIsUser == 'no'");

        assertThat(QuestionnaireSignals.active(questions, answers))
            .extracting(QuestionnaireQuestion::getId)
            .containsExactly("q1"); // q2 is not reachable until q1 is answered "no"

        answer("q1", "\"no\"");
        assertThat(QuestionnaireSignals.active(questions, answers))
            .extracting(QuestionnaireQuestion::getId)
            .containsExactly("q1", "q2");
    }
}
