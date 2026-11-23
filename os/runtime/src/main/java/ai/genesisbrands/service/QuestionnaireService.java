package ai.genesisbrands.service;

import ai.genesisbrands.model.Questionnaire;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import ai.genesisbrands.repository.QuestionnaireRepository;
import ai.genesisbrands.repository.QuestionnaireResponseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class QuestionnaireService {

    /** A signal key: dotted segments, optionally indexed, e.g. {@code belong.persona[0].story}. */
    private static final Pattern SIGNAL_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\[\\d+])?(\\.[A-Za-z][A-Za-z0-9_]*(\\[\\d+])?)*$");

    private static final Pattern SECTION_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*$");

    private final QuestionnaireRepository questionnaireRepo;
    private final QuestionnaireQuestionRepository questionRepo;
    private final QuestionnaireResponseRepository responseRepo;
    private final QuestionnaireAnswerRepository answerRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Everything that defines a question. A record rather than a parameter list because the
     * list had reached eleven arguments of mostly the same types, where a transposed pair
     * compiles cleanly and quietly swaps two fields.
     */
    public record QuestionSpec(QuestionnaireQuestion.Type type, String prompt, String helpText,
                               boolean required, boolean allowVoice, String configJson,
                               String signalKey, String sectionKey, String stepKey,
                               String conditionExpr) {
    }

    // ── Questionnaires ───────────────────────────────────────────────────────

    public List<Questionnaire> listQuestionnaires() {
        return questionnaireRepo.findAllByOrderByUpdatedAtDesc();
    }

    public Questionnaire getQuestionnaire(String id) {
        return questionnaireRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Questionnaire not found: " + id));
    }

    public Questionnaire createQuestionnaire(String title, String description) {
        Questionnaire q = new Questionnaire();
        q.setId(UUID.randomUUID().toString());
        q.setTitle(title);
        q.setDescription(description);
        return questionnaireRepo.save(q);
    }

    /**
     * A null {@code sectionsJson} leaves the existing structure alone and an empty string
     * clears it, rather than null meaning "clear". A caller that does not know about sections
     * — the admin page editing only a title, say — must not silently drop a questionnaire's
     * act structure as a side effect.
     */
    public Questionnaire updateQuestionnaire(String id, String title, String description, String sectionsJson) {
        Questionnaire q = getQuestionnaire(id);
        q.setTitle(title);
        q.setDescription(description);
        if (sectionsJson != null) {
            q.setSectionsJson(normaliseSections(sectionsJson));
        }
        q.setUpdatedAt(Instant.now());
        return questionnaireRepo.save(q);
    }

    /** Rejects malformed section definitions at the edit, not at the first client who hits them. */
    private String normaliseSections(String sectionsJson) {
        if (sectionsJson == null || sectionsJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(sectionsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("sectionsJson is not valid JSON: " + e.getMessage());
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("sectionsJson must be an array of {key, title, intent}");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode section : root) {
            String key = section.path("key").asText(null);
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Every section needs a key");
            }
            if (!SECTION_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Not a valid section key: " + key);
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Duplicate section key: " + key);
            }
            if (section.path("title").asText("").isBlank()) {
                throw new IllegalArgumentException("Section " + key + " needs a title");
            }
        }
        return sectionsJson;
    }

    /** The section keys declared by this questionnaire, empty when it has no acts. */
    private Set<String> sectionKeys(Questionnaire questionnaire) {
        Set<String> keys = new LinkedHashSet<>();
        String json = questionnaire.getSectionsJson();
        if (json == null || json.isBlank()) {
            return keys;
        }
        try {
            for (JsonNode section : objectMapper.readTree(json)) {
                String key = section.path("key").asText(null);
                if (key != null && !key.isBlank()) {
                    keys.add(key);
                }
            }
        } catch (Exception e) {
            return keys;
        }
        return keys;
    }

    @Transactional
    public void deleteQuestionnaire(String id) {
        for (var r : responseRepo.findByQuestionnaireIdOrderByCreatedAtDesc(id)) {
            for (var a : answerRepo.findByResponseIdOrderByCreatedAtAsc(r.getId())) {
                answerRepo.deleteById(a.getId());
            }
            responseRepo.deleteById(r.getId());
        }
        questionRepo.deleteByQuestionnaireId(id);
        questionnaireRepo.deleteById(id);
    }

    // ── Questions ─────────────────────────────────────────────────────────────

    public List<QuestionnaireQuestion> listQuestions(String questionnaireId) {
        return questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(questionnaireId);
    }

    /** The questions that apply to a response, given the answers it has so far. */
    public List<QuestionnaireQuestion> listActiveQuestions(String questionnaireId, String responseId) {
        return QuestionnaireSignals.active(
            listQuestions(questionnaireId),
            answerRepo.findByResponseIdOrderByCreatedAtAsc(responseId));
    }

    public QuestionnaireQuestion addQuestion(String questionnaireId, QuestionSpec spec) {
        getQuestionnaire(questionnaireId); // validate exists
        validate(questionnaireId, null, spec);
        int nextIndex = questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(questionnaireId).size();

        QuestionnaireQuestion question = new QuestionnaireQuestion();
        question.setId(UUID.randomUUID().toString());
        question.setQuestionnaireId(questionnaireId);
        question.setOrderIndex(nextIndex);
        apply(question, spec);
        touchQuestionnaire(questionnaireId);
        return questionRepo.save(question);
    }

    public QuestionnaireQuestion updateQuestion(String questionnaireId, String questionId, QuestionSpec spec) {
        QuestionnaireQuestion question = getQuestion(questionnaireId, questionId);
        validate(questionnaireId, questionId, spec);
        apply(question, spec);
        question.setUpdatedAt(Instant.now());
        touchQuestionnaire(questionnaireId);
        return questionRepo.save(question);
    }

    private void apply(QuestionnaireQuestion question, QuestionSpec spec) {
        question.setType(spec.type());
        question.setPrompt(spec.prompt());
        question.setHelpText(spec.helpText());
        question.setRequired(spec.required());
        question.setAllowVoice(spec.allowVoice());
        question.setConfigJson(spec.configJson());
        question.setSignalKey(trimToNull(spec.signalKey()));
        question.setSectionKey(trimToNull(spec.sectionKey()));
        question.setStepKey(trimToNull(spec.stepKey()));
        question.setConditionExpr(trimToNull(spec.conditionExpr()));
    }

    /**
     * Checks what can be checked about one question on its own: the signal key's shape, that
     * no other question in the questionnaire already claims it, and that the condition parses.
     * Whether a condition's <em>referent</em> exists is a whole-questionnaire question and is
     * checked at {@link #validateForPublish(String)} instead, so questions can be authored or
     * seeded in any order.
     */
    private void validate(String questionnaireId, String questionId, QuestionSpec spec) {
        if (spec.type() == null) {
            throw new IllegalArgumentException("A question needs a type");
        }
        if (spec.prompt() == null || spec.prompt().isBlank()) {
            throw new IllegalArgumentException("A question needs a prompt");
        }

        String signalKey = trimToNull(spec.signalKey());
        if (signalKey != null) {
            if (!SIGNAL_KEY.matcher(signalKey).matches()) {
                throw new IllegalArgumentException(
                    "Not a valid signal key: \"" + signalKey + "\". Expected dotted segments, "
                        + "optionally indexed, e.g. belief.origin or belong.persona[0].story");
            }
            boolean taken = questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(questionnaireId).stream()
                .anyMatch(q -> signalKey.equals(q.getSignalKey()) && !q.getId().equals(questionId));
            if (taken) {
                throw new IllegalArgumentException(
                    "Another question already captures the signal " + signalKey
                        + ". One signal, one question — two would silently overwrite each other.");
            }
        }

        String sectionKey = trimToNull(spec.sectionKey());
        if (sectionKey != null && !SECTION_KEY.matcher(sectionKey).matches()) {
            throw new IllegalArgumentException("Not a valid section key: " + sectionKey);
        }

        QuestionCondition.parse(trimToNull(spec.conditionExpr())); // throws when malformed
    }

    /**
     * Everything that can only be judged across the whole questionnaire. Returned as a list
     * rather than thrown so the admin page can show all of it at once, and enforced at
     * {@link #publish(String, Instant)} so a questionnaire with a dangling condition cannot
     * go live — a condition pointing at a signal nothing captures never fires, which silently
     * shows or hides a question for every client from then on.
     */
    public List<String> validateForPublish(String id) {
        Questionnaire questionnaire = getQuestionnaire(id);
        List<QuestionnaireQuestion> questions = listQuestions(id);
        List<String> issues = new ArrayList<>();

        if (questions.isEmpty()) {
            issues.add("The questionnaire has no questions.");
        }

        Set<String> captured = new LinkedHashSet<>();
        for (QuestionnaireQuestion q : questions) {
            if (q.getSignalKey() != null) {
                captured.add(q.getSignalKey());
            }
        }

        Set<String> declaredSections = sectionKeys(questionnaire);
        for (QuestionnaireQuestion q : questions) {
            String where = "Question " + (q.getOrderIndex() + 1);

            String expr = q.getConditionExpr();
            if (expr != null && !expr.isBlank()) {
                try {
                    QuestionCondition.parse(expr).ifPresent(clause -> {
                        if (!captured.contains(clause.signalKey())) {
                            issues.add(where + " is conditional on " + clause.signalKey()
                                + ", which no question in this questionnaire captures.");
                        }
                    });
                } catch (IllegalArgumentException e) {
                    issues.add(where + ": " + e.getMessage());
                }
            }

            if (!declaredSections.isEmpty() && q.getSectionKey() != null
                && !declaredSections.contains(q.getSectionKey())) {
                issues.add(where + " is in section \"" + q.getSectionKey()
                    + "\", which this questionnaire does not declare.");
            }
        }
        return issues;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public void deleteQuestion(String questionnaireId, String questionId) {
        getQuestion(questionnaireId, questionId);
        questionRepo.deleteById(questionId);
        touchQuestionnaire(questionnaireId);
    }

    @Transactional
    public void reorderQuestions(String questionnaireId, List<String> orderedQuestionIds) {
        getQuestionnaire(questionnaireId);
        for (int i = 0; i < orderedQuestionIds.size(); i++) {
            QuestionnaireQuestion question = getQuestion(questionnaireId, orderedQuestionIds.get(i));
            question.setOrderIndex(i);
            question.setUpdatedAt(Instant.now());
            questionRepo.save(question);
        }
        touchQuestionnaire(questionnaireId);
    }

    private QuestionnaireQuestion getQuestion(String questionnaireId, String questionId) {
        return questionRepo.findById(questionId)
            .filter(q -> q.getQuestionnaireId().equals(questionnaireId))
            .orElseThrow(() -> new NoSuchElementException("Question not found: " + questionId));
    }

    // ── Publishing ────────────────────────────────────────────────────────────

    @Transactional
    public Questionnaire publish(String id, Instant publishAt) {
        Questionnaire q = getQuestionnaire(id);

        List<String> issues = validateForPublish(id);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("Cannot publish:\n- " + String.join("\n- ", issues));
        }

        if (publishAt == null || !publishAt.isAfter(Instant.now())) {
            for (Questionnaire live : questionnaireRepo.findByStatus(Questionnaire.Status.PUBLISHED)) {
                if (!live.getId().equals(id)) {
                    live.setStatus(Questionnaire.Status.ARCHIVED);
                    live.setUpdatedAt(Instant.now());
                    questionnaireRepo.save(live);
                }
            }
            q.setStatus(Questionnaire.Status.PUBLISHED);
            q.setPublishAt(null);
            q.setPublishedAt(Instant.now());
        } else {
            q.setStatus(Questionnaire.Status.SCHEDULED);
            q.setPublishAt(publishAt);
        }
        q.setUpdatedAt(Instant.now());
        return questionnaireRepo.save(q);
    }

    public Questionnaire unpublish(String id) {
        Questionnaire q = getQuestionnaire(id);
        q.setStatus(Questionnaire.Status.DRAFT);
        q.setPublishAt(null);
        q.setPublishedAt(null);
        q.setUpdatedAt(Instant.now());
        return questionnaireRepo.save(q);
    }

    public Questionnaire getLive() {
        return questionnaireRepo.findByStatus(Questionnaire.Status.PUBLISHED).stream()
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("No questionnaire is currently published"));
    }

    List<Questionnaire> findScheduled() {
        return questionnaireRepo.findByStatus(Questionnaire.Status.SCHEDULED);
    }

    private void touchQuestionnaire(String id) {
        questionnaireRepo.findById(id).ifPresent(q -> {
            q.setUpdatedAt(Instant.now());
            questionnaireRepo.save(q);
        });
    }
}
