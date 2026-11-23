package ai.genesisbrands.service;

import ai.genesisbrands.model.Questionnaire;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import ai.genesisbrands.repository.QuestionnaireRepository;
import ai.genesisbrands.repository.QuestionnaireResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authoring guards. Each one exists because the failure it prevents is silent: two questions
 * claiming one signal overwrite each other's answer, a mistyped signal key produces a brief
 * with a hole in it, and a condition pointing at a signal nothing captures changes which
 * questions every client sees from then on without ever erroring.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionnaireServiceTest {

    @Mock private QuestionnaireRepository questionnaireRepo;
    @Mock private QuestionnaireQuestionRepository questionRepo;
    @Mock private QuestionnaireResponseRepository responseRepo;
    @Mock private QuestionnaireAnswerRepository answerRepo;

    private QuestionnaireService service;
    private Questionnaire questionnaire;
    private final List<QuestionnaireQuestion> existing = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new QuestionnaireService(questionnaireRepo, questionRepo, responseRepo, answerRepo);

        questionnaire = new Questionnaire();
        questionnaire.setId("qn");
        questionnaire.setTitle("Purpose discovery");

        when(questionnaireRepo.findById("qn")).thenReturn(Optional.of(questionnaire));
        when(questionRepo.findByQuestionnaireIdOrderByOrderIndexAsc("qn")).thenReturn(existing);
        when(questionRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(questionnaireRepo.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private QuestionnaireService.QuestionSpec spec(String signalKey, String conditionExpr) {
        return new QuestionnaireService.QuestionSpec(QuestionnaireQuestion.Type.SHORT_TEXT,
            "Why did you start?", null, true, false, null, signalKey, "belief", null, conditionExpr);
    }

    private void existingQuestion(String id, String signalKey, String conditionExpr, String sectionKey) {
        var q = new QuestionnaireQuestion();
        q.setId(id);
        q.setQuestionnaireId("qn");
        q.setOrderIndex(existing.size());
        q.setType(QuestionnaireQuestion.Type.SHORT_TEXT);
        q.setPrompt("prompt " + id);
        q.setSignalKey(signalKey);
        q.setConditionExpr(conditionExpr);
        q.setSectionKey(sectionKey);
        existing.add(q);
    }

    @Test
    @DisplayName("a question is stored with its signal, section, step and condition")
    void storesTheNewFields() {
        var saved = service.addQuestion("qn", new QuestionnaireService.QuestionSpec(
            QuestionnaireQuestion.Type.CHIP_PICK, "Pick your values", "Up to ten", true, false,
            "{\"bank\":[\"craft\"]}", "belief.valueChips", "belief", "values-step",
            "setup.businessName is present"));

        assertThat(saved.getSignalKey()).isEqualTo("belief.valueChips");
        assertThat(saved.getSectionKey()).isEqualTo("belief");
        assertThat(saved.getStepKey()).isEqualTo("values-step");
        assertThat(saved.getConditionExpr()).isEqualTo("setup.businessName is present");
        assertThat(saved.getType()).isEqualTo(QuestionnaireQuestion.Type.CHIP_PICK);
    }

    @Test
    @DisplayName("blank optional fields are stored as null, not as empty strings")
    void blankMeansUnset() {
        var saved = service.addQuestion("qn", new QuestionnaireService.QuestionSpec(
            QuestionnaireQuestion.Type.SHORT_TEXT, "Anything else?", null, false, false, null,
            "   ", "", "  ", ""));

        assertThat(saved.getSignalKey()).isNull();
        assertThat(saved.getSectionKey()).isNull();
        assertThat(saved.getStepKey()).isNull();
        assertThat(saved.getConditionExpr()).isNull();
    }

    @Test
    @DisplayName("an indexed signal key is valid; a malformed one is refused")
    void validatesSignalKeyShape() {
        assertThat(service.addQuestion("qn", spec("belong.persona[0].story", null)).getSignalKey())
            .isEqualTo("belong.persona[0].story");

        for (String bad : List.of("belief..origin", "1belief.origin", "belief.origin!",
                                   "belief origin", "belong.persona[].story", ".belief")) {
            assertThatThrownBy(() -> service.addQuestion("qn", spec(bad, null)))
                .as(bad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a valid signal key");
        }
    }

    @Test
    @DisplayName("two questions cannot claim the same signal")
    void refusesDuplicateSignal() {
        existingQuestion("q1", "belief.origin", null, "belief");

        assertThatThrownBy(() -> service.addQuestion("qn", spec("belief.origin", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already captures the signal");
        verify(questionRepo, never()).save(any());
    }

    @Test
    @DisplayName("a question keeping its own signal on update is not a duplicate of itself")
    void updateKeepsItsOwnSignal() {
        existingQuestion("q1", "belief.origin", null, "belief");
        when(questionRepo.findById("q1")).thenReturn(Optional.of(existing.get(0)));

        var saved = service.updateQuestion("qn", "q1", spec("belief.origin", null));
        assertThat(saved.getSignalKey()).isEqualTo("belief.origin");
    }

    @Test
    @DisplayName("a malformed condition is refused at the save")
    void refusesMalformedCondition() {
        assertThatThrownBy(() -> service.addQuestion("qn", spec("belief.origin", "belief.now ~ 'x'")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Not a valid condition");
    }

    @Test
    @DisplayName("a condition may be authored before the signal it refers to exists")
    void authoringOrderIsFree() {
        var saved = service.addQuestion("qn", spec("belong.buyer.role", "belong.buyerIsUser == 'no'"));
        assertThat(saved.getConditionExpr()).isEqualTo("belong.buyerIsUser == 'no'");
    }

    @Test
    @DisplayName("publishing refuses a condition that refers to a signal nothing captures")
    void publishCatchesDanglingConditions() {
        existingQuestion("q1", "belong.buyer.role", "belong.buyerIsUser == 'no'", null);

        assertThat(service.validateForPublish("qn"))
            .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .contains("belong.buyerIsUser")
            .contains("no question in this questionnaire captures");

        assertThatThrownBy(() -> service.publish("qn", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cannot publish");
    }

    @Test
    @DisplayName("publishing refuses a question in a section the questionnaire does not declare")
    void publishCatchesUnknownSections() {
        questionnaire.setSectionsJson("[{\"key\":\"belief\",\"title\":\"What you believe\"}]");
        existingQuestion("q1", "belief.origin", null, "belief");
        existingQuestion("q2", "belong.failedAlternatives", null, "belong");

        assertThat(service.validateForPublish("qn"))
            .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .contains("\"belong\"")
            .contains("does not declare");
    }

    @Test
    @DisplayName("a questionnaire whose conditions and sections all resolve publishes")
    void publishesWhenValid() {
        questionnaire.setSectionsJson("[{\"key\":\"belong\",\"title\":\"Who you belong to\"}]");
        existingQuestion("q1", "belong.buyerIsUser", null, "belong");
        existingQuestion("q2", "belong.buyer.role", "belong.buyerIsUser == 'no'", "belong");
        when(questionnaireRepo.findByStatus(Questionnaire.Status.PUBLISHED)).thenReturn(List.of());

        assertThat(service.validateForPublish("qn")).isEmpty();
        assertThat(service.publish("qn", null).getStatus()).isEqualTo(Questionnaire.Status.PUBLISHED);
    }

    @Test
    @DisplayName("an empty questionnaire cannot be published")
    void refusesEmptyQuestionnaire() {
        assertThat(service.validateForPublish("qn")).contains("The questionnaire has no questions.");
    }

    @Test
    @DisplayName("malformed section definitions are refused at the edit")
    void validatesSections() {
        for (String bad : List.of("not json", "{\"key\":\"belief\"}",
                                   "[{\"title\":\"No key\"}]",
                                   "[{\"key\":\"belief\"}]",
                                   "[{\"key\":\"a\",\"title\":\"A\"},{\"key\":\"a\",\"title\":\"B\"}]")) {
            assertThatThrownBy(() -> service.updateQuestionnaire("qn", "T", null, bad))
                .as(bad)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("an update that says nothing about sections leaves them alone")
    void sectionsSurviveATitleEdit() {
        questionnaire.setSectionsJson("[{\"key\":\"belief\",\"title\":\"What you believe\"}]");

        var saved = service.updateQuestionnaire("qn", "Renamed", "New description", null);

        assertThat(saved.getTitle()).isEqualTo("Renamed");
        assertThat(saved.getSectionsJson()).isEqualTo("[{\"key\":\"belief\",\"title\":\"What you believe\"}]");
    }

    @Test
    @DisplayName("an empty sections value clears the structure")
    void emptySectionsClears() {
        questionnaire.setSectionsJson("[{\"key\":\"belief\",\"title\":\"What you believe\"}]");

        assertThat(service.updateQuestionnaire("qn", "T", null, "").getSectionsJson()).isNull();
    }
}
