package ai.genesisbrands.service;

import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uploads. The filename comes from whoever is filling the questionnaire, so the property that
 * matters is that it never decides where the bytes land: every upload for a response must stay
 * inside that response's own folder, whatever the client calls the file.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionnaireResponseServiceTest {

    @Mock private QuestionnaireResponseRepository responseRepo;
    @Mock private QuestionnaireAnswerRepository answerRepo;
    @Mock private QuestionnaireService questionnaireService;
    @Mock private BlobStorageService blob;

    private QuestionnaireResponseService service;

    @BeforeEach
    void setUp() {
        service = new QuestionnaireResponseService(responseRepo, answerRepo, questionnaireService, blob);

        var response = new QuestionnaireResponse();
        response.setId("r1");
        response.setQuestionnaireId("qn");
        when(responseRepo.findById("r1")).thenReturn(Optional.of(response));
        when(answerRepo.findByResponseIdAndQuestionId("r1", "q1")).thenReturn(Optional.empty());
        when(answerRepo.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private List<String> uploadedPaths() throws IOException {
        var paths = ArgumentCaptor.forClass(String.class);
        verify(blob, org.mockito.Mockito.atLeastOnce()).upload(paths.capture(), any());
        return paths.getAllValues();
    }

    @Test
    @DisplayName("a filename with path separators cannot move the upload out of the response folder")
    void filenameCannotEscapeTheResponseFolder() throws IOException {
        var evil = new MockMultipartFile("image", "../../../etc/passwd.png", "image/png", "x".getBytes());

        service.saveImageAnswer("r1", "q1", evil);

        assertThat(uploadedPaths()).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .startsWith("questionnaires/qn/responses/r1/q1/")
            .doesNotContain("..")
            .endsWith("_passwd.png");
    }

    @Test
    @DisplayName("a missing or unusable filename still produces a usable key")
    void namelessUploadsStillWork() throws IOException {
        service.saveImageAnswer("r1", "q1", new MockMultipartFile("image", null, "image/png", "x".getBytes()));
        service.saveImageAnswer("r1", "q1", new MockMultipartFile("image", "...", "image/png", "x".getBytes()));

        assertThat(uploadedPaths()).allSatisfy(path ->
            assertThat(path).startsWith("questionnaires/qn/responses/r1/q1/").endsWith("_upload"));
    }

    @Test
    @DisplayName("a file-upload answer is a list, with each file's name, type and size")
    void fileAnswersAreAList() throws IOException {
        var logo = new MockMultipartFile("files", "logo.ai", "application/postscript", "a".getBytes());
        var sheet = new MockMultipartFile("files", "brand sheet.pdf", "application/pdf", "bb".getBytes());

        QuestionnaireAnswer saved = service.saveFileAnswers("r1", "q1", List.of(logo, sheet));

        assertThat(saved.getValueJson())
            .contains("\"name\":\"logo.ai\"")
            .contains("\"name\":\"brand_sheet.pdf\"")   // a space is not allowed into a blob key
            .contains("\"contentType\":\"application/pdf\"")
            .contains("\"size\":2")
            .startsWith("[");
    }

    @Test
    @DisplayName("an empty upload, or more files than an answer takes, is refused")
    void boundsTheUpload() {
        assertThatThrownBy(() -> service.saveFileAnswers("r1", "q1", List.of()))
            .isInstanceOf(IllegalArgumentException.class);

        var many = java.util.Collections.nCopies(11,
            (org.springframework.web.multipart.MultipartFile)
                new MockMultipartFile("files", "a.png", "image/png", "x".getBytes()));
        assertThatThrownBy(() -> service.saveFileAnswers("r1", "q1", many))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("At most 10");
    }
}
