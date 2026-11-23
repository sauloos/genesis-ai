package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.service.QuestionnaireResponseService;
import ai.genesisbrands.service.QuestionnaireService;
import ai.genesisbrands.service.QuestionnaireSignals;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Turns a stored questionnaire response into what the agent reads.
 *
 * <p>This lives in one place on purpose. There are two callers — the agent's own controller and
 * the engagement orchestrator — and the conversion is not trivial enough to duplicate: answers are
 * stored against question UUIDs, which questions were actually asked depends on the condition
 * grammar, and the signal keys rather than the ids are the contract with Layer 1. A second
 * implementation of this would drift, and the symptom would be a playbook quietly written about
 * the wrong set of answers.
 */
@Component
public class PurposePlaybookInputFactory {

    private final QuestionnaireService questionnaireService;
    private final QuestionnaireResponseService responseService;

    public PurposePlaybookInputFactory(QuestionnaireService questionnaireService,
                                       QuestionnaireResponseService responseService) {
        this.questionnaireService = questionnaireService;
        this.responseService = responseService;
    }

    /**
     * The agent's input for a stored response.
     *
     * <p>{@code expectedSignals} comes from the <em>active</em> questions, not all of them: a
     * question a condition gated away was never asked, and reporting it as unanswered would have
     * the playbook tell a client they skipped something they never saw.
     *
     * @param engagementId the owning engagement, or null for a run with no engagement (the
     *                     Playground), which is then labelled by the response id
     * @throws java.util.NoSuchElementException when there is no such response
     */
    public PurposePlaybookInput forResponse(String responseId, String engagementId,
                                            List<String> trainingInstructions,
                                            String additionalContext) {
        QuestionnaireResponse response = responseService.getResponse(responseId);
        List<QuestionnaireQuestion> active =
            questionnaireService.listActiveQuestions(response.getQuestionnaireId(), responseId);
        List<QuestionnaireAnswer> answers = responseService.listAnswers(responseId);

        String label = engagementId == null || engagementId.isBlank()
            ? "response-" + responseId
            : engagementId;
        return from(label, active, answers, trainingInstructions, additionalContext);
    }

    /**
     * The same input from questions and answers already in hand, for a caller that never had a
     * stored response to load (a PageFlow's captured answers).
     *
     * @param questions the questions actually asked, in order
     */
    public PurposePlaybookInput from(String engagementId,
                                     List<QuestionnaireQuestion> questions,
                                     List<QuestionnaireAnswer> answers,
                                     List<String> trainingInstructions,
                                     String additionalContext) {
        Map<String, Object> signals = QuestionnaireSignals.collect(questions, answers);
        List<String> expected = signalKeys(questions);
        return new PurposePlaybookInput(engagementId, signals, expected,
            trainingInstructions, additionalContext);
    }

    /**
     * The distinct signal keys a set of questions declares.
     *
     * <p>Also the test for whether a questionnaire can produce a playbook at all. The pre-Purpose
     * Playbook instruments carry no signal keys, so their answers reach Layer 1 under no name the
     * modules recognise: running the agent on one would produce a confident document about
     * nothing. An empty list here is what sends the orchestrator down the legacy path instead.
     */
    public static List<String> signalKeys(List<QuestionnaireQuestion> questions) {
        if (questions == null) {
            return List.of();
        }
        return questions.stream()
            .map(QuestionnaireQuestion::getSignalKey)
            .filter(key -> key != null && !key.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }
}
