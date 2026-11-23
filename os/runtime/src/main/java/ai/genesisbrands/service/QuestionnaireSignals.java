package ai.genesisbrands.service;

import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a response's answers into a map keyed by Layer 1 signal key.
 *
 * <p>Answers are stored against a question's UUID, which is the right thing for storage and
 * the wrong thing for every consumer: an agent, an evaluator or a client's RAG context cares
 * that it has {@code belief.origin}, not that question {@code 7f3a…} was answered. Keying on
 * the signal is what lets a question be reworded, reordered, split or replaced without
 * touching anything downstream — the signal keys are the contract, the question rows are an
 * implementation detail of how they were collected.
 *
 * <p>Values keep their JSON shape: a multi-select stays a list, an upload stays a map with
 * its blob path, a slider stays a number. Conditions and prompt-builders both need the real
 * shape rather than a stringified one.
 */
public final class QuestionnaireSignals {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QuestionnaireSignals() {
    }

    /**
     * Collects answers by signal key, in question order.
     *
     * <p>Questions with no signal key are skipped — they carry no catalogued meaning, so
     * there is nothing for a consumer to key on. Unanswered and empty answers are left out
     * entirely rather than mapped to null, so {@code containsKey} means "answered".
     */
    public static Map<String, Object> collect(List<QuestionnaireQuestion> questions,
                                              List<QuestionnaireAnswer> answers) {
        Map<String, QuestionnaireAnswer> byQuestionId = answers == null ? Map.of() : answers.stream()
            .collect(Collectors.toMap(QuestionnaireAnswer::getQuestionId, Function.identity(),
                (first, second) -> second));

        Map<String, Object> signals = new LinkedHashMap<>();
        if (questions == null) {
            return signals;
        }
        for (QuestionnaireQuestion question : questions) {
            String signalKey = question.getSignalKey();
            if (signalKey == null || signalKey.isBlank()) {
                continue;
            }
            QuestionnaireAnswer answer = byQuestionId.get(question.getId());
            if (answer == null) {
                continue;
            }
            Object value = parse(answer.getValueJson());
            if (QuestionCondition.isPresent(value)) {
                signals.put(signalKey.trim(), value);
            }
        }
        return signals;
    }

    /**
     * Which of a questionnaire's questions apply, given the answers so far.
     *
     * <p>Evaluated server-side and nowhere else. A second implementation in the browser would
     * be a second place for the rules to drift, and the client is not a trustworthy place to
     * decide which questions a brief is allowed to be missing.
     */
    public static List<QuestionnaireQuestion> active(List<QuestionnaireQuestion> questions,
                                                     List<QuestionnaireAnswer> answers) {
        Map<String, Object> signals = collect(questions, answers);
        return questions == null ? List.of() : questions.stream()
            .filter(q -> QuestionCondition.applies(q.getConditionExpr(), signals))
            .toList();
    }

    /** A stored answer value, as whatever JSON type it was saved as. */
    private static Object parse(String valueJson) {
        if (valueJson == null || valueJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(valueJson, Object.class);
        } catch (Exception e) {
            return valueJson; // a legacy answer saved as a bare string
        }
    }
}
