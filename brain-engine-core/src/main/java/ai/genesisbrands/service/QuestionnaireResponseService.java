package ai.genesisbrands.service;

import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuestionnaireResponseService {

    private final QuestionnaireResponseRepository responseRepo;
    private final QuestionnaireAnswerRepository answerRepo;
    private final QuestionnaireService questionnaireService;
    private final BlobStorageService blob;

    @Value("${openai.api-key:${spring.ai.openai.api-key:}}")
    private String openAiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<QuestionnaireResponse> listResponses(String questionnaireId) {
        return responseRepo.findByQuestionnaireIdOrderByCreatedAtDesc(questionnaireId);
    }

    public List<QuestionnaireAnswer> listAnswers(String responseId) {
        return answerRepo.findByResponseIdOrderByCreatedAtAsc(responseId);
    }

    public QuestionnaireResponse getResponse(String responseId) {
        return responseRepo.findById(responseId)
            .orElseThrow(() -> new NoSuchElementException("Response not found: " + responseId));
    }

    public QuestionnaireResponse startResponse(String questionnaireId) {
        questionnaireService.getQuestionnaire(questionnaireId); // validate exists
        QuestionnaireResponse response = new QuestionnaireResponse();
        response.setId(UUID.randomUUID().toString());
        response.setQuestionnaireId(questionnaireId);
        return responseRepo.save(response);
    }

    public QuestionnaireAnswer saveAnswer(String responseId, String questionId, Object value) {
        try {
            return saveRawAnswer(responseId, questionId, objectMapper.writeValueAsString(value));
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize answer value", e);
        }
    }

    public QuestionnaireAnswer saveImageAnswer(String responseId, String questionId, MultipartFile image) throws IOException {
        QuestionnaireResponse response = getResponse(responseId);
        String blobPath = "questionnaires/" + response.getQuestionnaireId() + "/responses/" + responseId
            + "/" + questionId + "/" + UUID.randomUUID() + "_" + image.getOriginalFilename();
        blob.upload(blobPath, image.getBytes());
        return saveAnswer(responseId, questionId, Map.of("blobPath", "/api/assets/" + blobPath));
    }

    public QuestionnaireAnswer saveVoiceAnswer(String responseId, String questionId, MultipartFile audio) throws IOException {
        QuestionnaireResponse response = getResponse(responseId);
        String blobPath = "questionnaires/" + response.getQuestionnaireId() + "/responses/" + responseId
            + "/" + questionId + "/" + UUID.randomUUID() + "_" + audio.getOriginalFilename();
        blob.upload(blobPath, audio.getBytes());
        String transcript = transcribe(audio);
        return saveAnswer(responseId, questionId, transcript);
    }

    public QuestionnaireResponse submit(String responseId) {
        QuestionnaireResponse response = getResponse(responseId);
        response.setStatus(QuestionnaireResponse.Status.SUBMITTED);
        response.setSubmittedAt(Instant.now());
        response.setUpdatedAt(Instant.now());
        return responseRepo.save(response);
    }

    private QuestionnaireAnswer saveRawAnswer(String responseId, String questionId, String valueJson) {
        getResponse(responseId); // validate exists
        QuestionnaireAnswer answer = answerRepo.findByResponseIdAndQuestionId(responseId, questionId)
            .orElseGet(() -> {
                QuestionnaireAnswer a = new QuestionnaireAnswer();
                a.setId(UUID.randomUUID().toString());
                a.setResponseId(responseId);
                a.setQuestionId(questionId);
                return a;
            });
        answer.setValueJson(valueJson);
        answer.setUpdatedAt(Instant.now());
        return answerRepo.save(answer);
    }

    @SuppressWarnings("unchecked")
    private String transcribe(MultipartFile audio) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(openAiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("model", "whisper-1");
        byte[] audioBytes = audio.getBytes();
        String audioName = audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "audio.webm";
        body.add("file", new ByteArrayResource(audioBytes) {
            @Override public String getFilename() { return audioName; }
        });

        ResponseEntity<Map> response = restTemplate.exchange(
            "https://api.openai.com/v1/audio/transcriptions",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class
        );

        Map<String, Object> result = response.getBody();
        return result != null ? (String) result.get("text") : "";
    }
}
