package ai.genesisbrands.service;

import ai.genesisbrands.model.TrainingContent;
import ai.genesisbrands.model.TrainingContent.ContentType;
import ai.genesisbrands.model.TrainingSession;
import ai.genesisbrands.model.TrainingSession.Status;
import ai.genesisbrands.repository.TrainingContentRepository;
import ai.genesisbrands.repository.TrainingSessionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TrainingService {

    private final TrainingSessionRepository sessionRepo;
    private final TrainingContentRepository contentRepo;
    private final BlobStorageService blob;
    private final EmbeddingModel embeddingModel;
    private final ChatClient.Builder chatClientBuilder;

    @Value("${openai.api-key:${spring.ai.openai.api-key:}}")
    private String openAiKey;

    @Value("${qdrant.rest.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.rest.api-key:}")
    private String qdrantApiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:genesis-knowledge}")
    private String collectionName;

    @Value("${genesis.training.interpretation.enabled:true}")
    private boolean interpretationEnabled;

    @Value("${genesis.training.interpretation.model:claude-haiku-4-5}")
    private String interpretationModel;

    private final RestTemplate restTemplate = new RestTemplate();
    private ChatClient chatClient;

    @PostConstruct
    private void init() {
        chatClient = chatClientBuilder.build();
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    public List<TrainingSession> listSessions() {
        return sessionRepo.findAllByOrderByUpdatedAtDesc();
    }

    public TrainingSession getSession(String id) {
        return sessionRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Training session not found: " + id));
    }

    public TrainingSession createSession(String topic) {
        TrainingSession session = new TrainingSession();
        session.setId(UUID.randomUUID().toString());
        session.setTopic(topic);
        return sessionRepo.save(session);
    }

    public TrainingSession updateSession(String id, String topic, TrainingSession.Intent intent,
                                         TrainingSession.Scope scope, String assetTypes) {
        TrainingSession session = getSession(id);
        session.setTopic(topic);
        session.setIntent(intent);
        session.setScope(scope);
        session.setAssetTypes(assetTypes);
        session.setUpdatedAt(Instant.now());
        return sessionRepo.save(session);
    }

    @Transactional
    public void deleteSession(String id) {
        List<TrainingContent> contents = contentRepo.findBySessionIdOrderByCreatedAtAsc(id);
        for (TrainingContent c : contents) {
            if (c.getBlobPath() != null) blob.delete(c.getBlobPath());
        }
        contentRepo.deleteBySessionId(id);
        sessionRepo.deleteById(id);
    }

    // ── Content ───────────────────────────────────────────────────────────────

    public List<TrainingContent> listContent(String sessionId) {
        return contentRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public TrainingContent addText(String sessionId, String text) {
        getSession(sessionId); // validate exists
        TrainingContent content = new TrainingContent();
        content.setId(UUID.randomUUID().toString());
        content.setSessionId(sessionId);
        content.setContentType(ContentType.TEXT);
        content.setTextContent(text);
        touchSession(sessionId);
        return contentRepo.save(content);
    }

    public TrainingContent addFile(String sessionId, MultipartFile file) throws IOException {
        getSession(sessionId);
        String blobPath = "training/" + sessionId + "/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
        blob.upload(blobPath, file.getBytes());

        TrainingContent content = new TrainingContent();
        content.setId(UUID.randomUUID().toString());
        content.setSessionId(sessionId);
        content.setContentType(ContentType.FILE);
        content.setBlobPath(blobPath);
        content.setFileName(file.getOriginalFilename());
        touchSession(sessionId);
        return contentRepo.save(content);
    }

    public TrainingContent addAudio(String sessionId, MultipartFile audio) throws IOException {
        TrainingSession session = getSession(sessionId);
        String blobPath = "training/" + sessionId + "/" + UUID.randomUUID() + "_" + audio.getOriginalFilename();
        blob.upload(blobPath, audio.getBytes());

        String transcript = transcribe(audio);
        String interpreted = interpretationEnabled ? interpret(transcript, session) : null;

        TrainingContent content = new TrainingContent();
        content.setId(UUID.randomUUID().toString());
        content.setSessionId(sessionId);
        content.setContentType(ContentType.AUDIO);
        content.setBlobPath(blobPath);
        content.setFileName(audio.getOriginalFilename());
        content.setTranscript(transcript);
        content.setTextContent(interpreted);
        touchSession(sessionId);
        return contentRepo.save(content);
    }

    public void deleteContent(String sessionId, String contentId) {
        TrainingContent content = contentRepo.findById(contentId)
            .filter(c -> c.getSessionId().equals(sessionId))
            .orElseThrow(() -> new NoSuchElementException("Content not found: " + contentId));
        if (content.getBlobPath() != null) blob.delete(content.getBlobPath());
        contentRepo.deleteById(contentId);
        touchSession(sessionId);
    }

    // ── Ingest ────────────────────────────────────────────────────────────────

    public void ingest(String sessionId) {
        TrainingSession session = getSession(sessionId);
        List<TrainingContent> contents = contentRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);

        for (TrainingContent c : contents) {
            String text = resolveText(c);
            if (text == null || text.isBlank()) continue;

            Map<String, Object> payload = buildPayload(session, c, text);
            upsertToQdrant(text, payload, UUID.randomUUID().toString());
        }

        session.setStatus(Status.INGESTED);
        session.setUpdatedAt(Instant.now());
        sessionRepo.save(session);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private String resolveText(TrainingContent c) {
        return switch (c.getContentType()) {
            case TEXT  -> c.getTextContent();
            case AUDIO -> c.getTextContent() != null ? c.getTextContent() : c.getTranscript();
            case FILE  -> null; // file text extraction not yet implemented — future: PDFBox
        };
    }

    private String interpret(String rawTranscript, TrainingSession session) {
        String systemPrompt = """
            You are a knowledge structuring assistant. You receive a raw voice transcript from a brand training session.
            Clean up transcription artefacts, filler words, and run-on sentences, then restructure the content as
            clear, coherent prose that preserves every piece of information, intent, and nuance from the speaker.
            Do not add, infer, or omit anything. Output only the structured content — no preamble, no commentary.
            """;

        String userPrompt = String.format(
            "Topic: %s | Intent: %s | Scope: %s\n\nTranscript:\n%s",
            session.getTopic(), session.getIntent().name(), session.getScope().name(), rawTranscript
        );

        return chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .options(AnthropicChatOptions.builder().model(interpretationModel).build())
            .call()
            .content();
    }

    private Map<String, Object> buildPayload(TrainingSession session, TrainingContent c, String text) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("text",        text);
        payload.put("source_type", "training");
        payload.put("session_id",  session.getId());
        payload.put("topic",       session.getTopic());
        payload.put("intent",      session.getIntent().name());
        payload.put("scope",       session.getScope().name());
        payload.put("asset_types", session.getAssetTypes() != null ? session.getAssetTypes() : "");
        payload.put("content_type", c.getContentType().name());
        payload.put("layer",       "training");
        return payload;
    }

    private void upsertToQdrant(String text, Map<String, Object> payload, String pointId) {
        float[] embedding = embeddingModel.embed(text);
        List<Float> vector = new ArrayList<>(embedding.length);
        for (float f : embedding) vector.add(f);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (qdrantApiKey != null && !qdrantApiKey.isBlank())
            headers.set("api-key", qdrantApiKey);

        Map<String, Object> point = Map.of("id", pointId, "vector", vector, "payload", payload);
        Map<String, Object> body  = Map.of("points", List.of(point));
        String url = qdrantUrl.replaceAll("/$", "") + "/collections/" + collectionName + "/points";
        restTemplate.put(url, new HttpEntity<>(body, headers));
    }

    @SuppressWarnings("unchecked")
    private String transcribe(MultipartFile audio) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(openAiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("model", "whisper-1");
        byte[] audioBytes = audio.getBytes();
        String audioName  = audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "audio.webm";
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

    private void touchSession(String sessionId) {
        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setUpdatedAt(Instant.now());
            sessionRepo.save(s);
        });
    }
}
