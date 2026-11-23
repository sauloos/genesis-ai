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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuestionnaireResponseService {

    private static final int MAX_FILES_PER_ANSWER = 10;

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

    public QuestionnaireResponse startResponse(String questionnaireId, boolean simulated) {
        questionnaireService.getQuestionnaire(questionnaireId); // validate exists
        QuestionnaireResponse response = new QuestionnaireResponse();
        response.setId(UUID.randomUUID().toString());
        response.setQuestionnaireId(questionnaireId);
        response.setSimulated(simulated);
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
        String blobPath = store(responseId, questionId, image);
        return saveAnswer(responseId, questionId, Map.of("blobPath", "/api/assets/" + blobPath));
    }

    /**
     * Several files under one answer, as a list — "your current logo" arrives as an .ai plus a
     * PDF plus a PNG more often than it arrives as one file. The single-image answer above
     * keeps its object shape, because existing responses and briefs are stored that way.
     */
    public QuestionnaireAnswer saveFileAnswers(String responseId, String questionId,
                                                List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files were uploaded.");
        }
        if (files.size() > MAX_FILES_PER_ANSWER) {
            throw new IllegalArgumentException("At most " + MAX_FILES_PER_ANSWER + " files per answer.");
        }
        List<Map<String, Object>> stored = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String blobPath = store(responseId, questionId, file);
            stored.add(Map.of(
                "blobPath", "/api/assets/" + blobPath,
                "name", safeName(file.getOriginalFilename()),
                "contentType", file.getContentType() != null ? file.getContentType() : "application/octet-stream",
                "size", file.getSize()));
        }
        return saveAnswer(responseId, questionId, stored);
    }

    public QuestionnaireAnswer saveVoiceAnswer(String responseId, String questionId, MultipartFile audio) throws IOException {
        store(responseId, questionId, audio);
        String transcript = transcribe(audio);
        return saveAnswer(responseId, questionId, transcript);
    }

    private String store(String responseId, String questionId, MultipartFile file) throws IOException {
        QuestionnaireResponse response = getResponse(responseId);
        String blobPath = "questionnaires/" + response.getQuestionnaireId() + "/responses/" + responseId
            + "/" + questionId + "/" + UUID.randomUUID() + "_" + safeName(file.getOriginalFilename());
        blob.upload(blobPath, file.getBytes());
        return blobPath;
    }

    /**
     * The uploader chooses this name, so it is never allowed into a storage key as given: a
     * name carrying path separators would write outside the response's own folder.
     */
    private static String safeName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) return "upload";
        String base = originalFilename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        while (base.startsWith(".")) base = base.substring(1);
        if (base.isBlank()) return "upload";
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
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
