package ai.genesisbrands.service;

import ai.genesisbrands.model.Questionnaire;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import ai.genesisbrands.repository.QuestionnaireRepository;
import ai.genesisbrands.repository.QuestionnaireResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuestionnaireService {

    private final QuestionnaireRepository questionnaireRepo;
    private final QuestionnaireQuestionRepository questionRepo;
    private final QuestionnaireResponseRepository responseRepo;
    private final QuestionnaireAnswerRepository answerRepo;

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

    public Questionnaire updateQuestionnaire(String id, String title, String description) {
        Questionnaire q = getQuestionnaire(id);
        q.setTitle(title);
        q.setDescription(description);
        q.setUpdatedAt(Instant.now());
        return questionnaireRepo.save(q);
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

    public QuestionnaireQuestion addQuestion(String questionnaireId, QuestionnaireQuestion.Type type, String prompt,
                                              String helpText, boolean required, boolean allowVoice, String configJson) {
        getQuestionnaire(questionnaireId); // validate exists
        int nextIndex = questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(questionnaireId).size();

        QuestionnaireQuestion question = new QuestionnaireQuestion();
        question.setId(UUID.randomUUID().toString());
        question.setQuestionnaireId(questionnaireId);
        question.setOrderIndex(nextIndex);
        question.setType(type);
        question.setPrompt(prompt);
        question.setHelpText(helpText);
        question.setRequired(required);
        question.setAllowVoice(allowVoice);
        question.setConfigJson(configJson);
        touchQuestionnaire(questionnaireId);
        return questionRepo.save(question);
    }

    public QuestionnaireQuestion updateQuestion(String questionnaireId, String questionId, QuestionnaireQuestion.Type type,
                                                 String prompt, String helpText, boolean required, boolean allowVoice,
                                                 String configJson) {
        QuestionnaireQuestion question = getQuestion(questionnaireId, questionId);
        question.setType(type);
        question.setPrompt(prompt);
        question.setHelpText(helpText);
        question.setRequired(required);
        question.setAllowVoice(allowVoice);
        question.setConfigJson(configJson);
        question.setUpdatedAt(Instant.now());
        touchQuestionnaire(questionnaireId);
        return questionRepo.save(question);
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
