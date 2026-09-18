package ai.genesisbrands.service;

import ai.genesisbrands.model.PlaygroundSession;
import ai.genesisbrands.model.TrainingContent;
import ai.genesisbrands.model.TrainingContent.ContentType;
import ai.genesisbrands.model.TrainingSession;
import ai.genesisbrands.model.TrainingSession.Intent;
import ai.genesisbrands.model.TrainingSession.Status;
import ai.genesisbrands.repository.TrainingContentRepository;
import ai.genesisbrands.repository.TrainingSessionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrainingService {

    private final TrainingSessionRepository sessionRepo;
    private final TrainingContentRepository contentRepo;
    private final BlobStorageService blob;
    private final EmbeddingModel embeddingModel;
    private final ChatClient.Builder chatClientBuilder;
    private final PlaygroundSessionService playgroundSessionService;

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

    public TrainingContent addFile(String sessionId, MultipartFile file, String userContext) throws IOException {
        getSession(sessionId);
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String blobPath = "training/" + sessionId + "/" + UUID.randomUUID() + "_" + originalName;
        byte[] bytes = file.getBytes();
        blob.upload(blobPath, bytes);

        TrainingContent content = new TrainingContent();
        content.setId(UUID.randomUUID().toString());
        content.setSessionId(sessionId);
        content.setFileName(originalName);
        content.setBlobPath(blobPath);

        String lower = originalName.toLowerCase();
        boolean isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                       || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".avif");

        if (isImage) {
            // Describe the image directly with Claude Vision
            content.setContentType(ContentType.ASSET);
            content.setTextContent(describeImageBytes(bytes, imageMimeType(originalName), userContext));
        } else if (lower.endsWith(".pdf")) {
            // Extract text; image extraction happens at ingest time via pipeline
            content.setContentType(ContentType.FILE);
            if (!userContext.isBlank()) content.setTextContent(userContext);
        } else {
            content.setContentType(ContentType.FILE);
            if (!userContext.isBlank()) content.setTextContent(userContext);
        }

        touchSession(sessionId);
        return contentRepo.save(content);
    }

    public TrainingContent addFile(String sessionId, MultipartFile file) throws IOException {
        return addFile(sessionId, file, "");
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

    public TrainingContent addUrl(String sessionId, String url, String userContext) throws IOException {
        getSession(sessionId);

        UrlContentKind kind = detectUrlKind(url);
        String extractedText = switch (kind) {
            case IMAGE -> describeImageUrl(url, userContext);
            case PDF   -> fetchPdfText(url);
            default    -> fetchWebContent(url, userContext);
        };

        TrainingContent content = new TrainingContent();
        content.setId(UUID.randomUUID().toString());
        content.setSessionId(sessionId);
        content.setContentType(ContentType.URL);
        content.setFileName(url);
        content.setTextContent(extractedText);
        touchSession(sessionId);
        return contentRepo.save(content);
    }

    public TrainingContent addUrl(String sessionId, String url) throws IOException {
        return addUrl(sessionId, url, "");
    }

    private enum UrlContentKind { IMAGE, PDF, WEB }

    private UrlContentKind detectUrlKind(String url) {
        String lower = url.toLowerCase().split("\\?")[0];
        for (String ext : new String[]{".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".svg"}) {
            if (lower.endsWith(ext)) return UrlContentKind.IMAGE;
        }
        if (lower.endsWith(".pdf")) return UrlContentKind.PDF;
        return UrlContentKind.WEB;
    }

    private MimeType imageMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png"))  return MimeTypeUtils.IMAGE_PNG;
        if (lower.contains(".gif"))  return MimeTypeUtils.IMAGE_GIF;
        if (lower.contains(".webp")) return MimeType.valueOf("image/webp");
        if (lower.contains(".svg"))  return MimeType.valueOf("image/svg+xml");
        return MimeTypeUtils.IMAGE_JPEG;
    }

    private String describeImageUrl(String imageUrl, String userContext) {
        try {
            MimeType mime = imageMimeType(imageUrl);
            byte[] bytes = restTemplate.getForObject(imageUrl, byte[].class);
            if (bytes == null || bytes.length == 0) return "Image reference: " + imageUrl;
            return describeImageBytes(bytes, mime, userContext);
        } catch (Exception e) {
            log.warn("Image description failed for {}: {}", imageUrl, e.getMessage());
            return "Visual reference: " + imageUrl;
        }
    }

    private String describeImageBytes(byte[] bytes, MimeType mime, String userContext) {
        String contextPrefix = (userContext != null && !userContext.isBlank())
            ? "Context from the person who shared this image: \"" + userContext + "\"\n"
              + "Take this context into account — it may identify the asset's role "
              + "(e.g. 'this is our logo', 'colour palette reference').\n\n"
            : "";
        String prompt = contextPrefix + """
            You are a brand intelligence analyst examining a visual asset.
            First identify what type of visual this is (logo, photograph, illustration,
            chart, pattern, mood board, icon, typography specimen, colour palette, etc.).
            Then describe it in detail useful for an AI knowledge base: visual style,
            colours with approximate hex values, typography if present, composition,
            iconography, mood, brand personality signals, industry/audience positioning.
            Be specific. Output plain prose, no bullet headers.""";
        try {
            var media = new Media(mime, new ByteArrayResource(bytes));
            var message = UserMessage.builder().text(prompt).media(media).build();
            return chatClient.prompt()
                .messages(message)
                .options(AnthropicChatOptions.builder().model("claude-sonnet-5").build())
                .call()
                .content();
        } catch (Exception e) {
            log.warn("Image description failed: {}", e.getMessage());
            return userContext.isBlank() ? "Visual asset" : "Visual asset: " + userContext;
        }
    }

    private String fetchPdfText(String pdfUrl) {
        try {
            byte[] bytes = restTemplate.getForObject(pdfUrl, byte[].class);
            if (bytes == null) return "PDF reference: " + pdfUrl;
            PDDocument doc = PDDocument.load(bytes);
            String text = new PDFTextStripper().getText(doc);
            doc.close();
            return text;
        } catch (Exception e) {
            log.warn("PDF fetch failed for {}: {}", pdfUrl, e.getMessage());
            return "PDF reference (fetch failed): " + pdfUrl;
        }
    }

    private String fetchWebText(String webUrl) {
        return fetchWebContent(webUrl, "");
    }

    private String fetchWebContent(String webUrl, String userContext) {
        try {
            Document doc = Jsoup.connect(webUrl)
                .userAgent("Mozilla/5.0 (compatible; GenesisAI/1.0)")
                .timeout(12_000)
                .get();
            String title = doc.title();
            String body = doc.body().text();
            if (body.length() > 40_000) body = body.substring(0, 40_000);
            String text = title.isBlank() ? body : title + "\n\n" + body;

            // Extract and describe images from the page
            List<String> imageDescriptions = extractWebImageDescriptions(doc, webUrl, userContext);
            if (!imageDescriptions.isEmpty()) {
                StringBuilder sb = new StringBuilder(text);
                for (int i = 0; i < imageDescriptions.size(); i++) {
                    sb.append("\n\n[Visual asset ").append(i + 1).append("]: ")
                      .append(imageDescriptions.get(i));
                }
                text = sb.toString();
            }
            return text;
        } catch (Exception e) {
            log.warn("Web fetch failed for {}: {}", webUrl, e.getMessage());
            return "Web reference (fetch failed): " + webUrl;
        }
    }

    private static final int MAX_WEB_IMAGES = 8;

    private List<String> extractWebImageDescriptions(Document doc, String pageUrl, String userContext) {
        List<String> descriptions = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();

        // OG image first — usually the logo or hero
        doc.select("meta[property=og:image]").stream()
            .map(el -> el.attr("content"))
            .filter(s -> !s.isBlank())
            .forEach(seen::add);
        doc.select("img[src]").stream()
            .map(el -> el.absUrl("src"))
            .filter(s -> !s.isBlank())
            .forEach(seen::add);

        for (String imgUrl : seen) {
            if (descriptions.size() >= MAX_WEB_IMAGES) break;
            String lower = imgUrl.toLowerCase().split("\\?")[0];
            if (lower.endsWith(".svg") || lower.endsWith(".ico")) continue;
            try {
                byte[] bytes = restTemplate.getForObject(imgUrl, byte[].class);
                if (bytes == null || bytes.length < 2_000 || bytes.length > 5_000_000) continue;
                String desc = describeImageBytes(bytes, imageMimeType(imgUrl), userContext);
                if (desc != null && !desc.isBlank()) descriptions.add(desc);
            } catch (Exception e) {
                log.debug("Skipping web image {}: {}", imgUrl, e.getMessage());
            }
        }
        return descriptions;
    }

    public void deleteContent(String sessionId, String contentId) {
        TrainingContent content = contentRepo.findById(contentId)
            .filter(c -> c.getSessionId().equals(sessionId))
            .orElseThrow(() -> new NoSuchElementException("Content not found: " + contentId));
        if (content.getBlobPath() != null) blob.delete(content.getBlobPath());
        contentRepo.deleteById(contentId);
        touchSession(sessionId);
    }

    // ── Feedback (playground-sourced, asset-specific) ────────────────────────────

    public TrainingSession submitFeedback(String playgroundSessionId, String assetType, TrainingContent.Label label,
                                           String feedbackText, MultipartFile audio, String agentReasoning,
                                           List<String> assetUrls) throws IOException {
        String topic = "Feedback: " + assetType;
        try {
            PlaygroundSession pg = playgroundSessionService.get(playgroundSessionId);
            topic = "Feedback: " + assetType + " — " + pg.getLabel();
        } catch (Exception ignored) {
            // best-effort topic derivation; the source id is still recorded below
        }

        TrainingSession session = new TrainingSession();
        session.setId(UUID.randomUUID().toString());
        session.setTopic(topic);
        session.setIntent(Intent.FEEDBACK);
        session.setScope(TrainingSession.Scope.ASSET_SCOPED);
        session.setAssetTypes(assetType);
        session.setStatus(Status.PENDING_REVIEW);
        session.setSourcePlaygroundSessionId(playgroundSessionId);
        session = sessionRepo.save(session);

        if (agentReasoning != null && !agentReasoning.isBlank()) {
            TrainingContent reasoning = new TrainingContent();
            reasoning.setId(UUID.randomUUID().toString());
            reasoning.setSessionId(session.getId());
            reasoning.setContentType(ContentType.TEXT);
            reasoning.setTextContent("Agent's own reasoning:\n" + agentReasoning);
            contentRepo.save(reasoning);
        }

        if (audio != null) {
            String blobPath = "training/" + session.getId() + "/" + UUID.randomUUID() + "_" + audio.getOriginalFilename();
            blob.upload(blobPath, audio.getBytes());
            String transcript = transcribe(audio);
            String interpreted = interpretationEnabled ? interpret(transcript, session) : null;

            TrainingContent feedback = new TrainingContent();
            feedback.setId(UUID.randomUUID().toString());
            feedback.setSessionId(session.getId());
            feedback.setContentType(ContentType.AUDIO);
            feedback.setBlobPath(blobPath);
            feedback.setFileName(audio.getOriginalFilename());
            feedback.setTranscript(transcript);
            feedback.setTextContent(interpreted);
            feedback.setLabel(label);
            contentRepo.save(feedback);
        } else if (feedbackText != null && !feedbackText.isBlank()) {
            TrainingContent feedback = new TrainingContent();
            feedback.setId(UUID.randomUUID().toString());
            feedback.setSessionId(session.getId());
            feedback.setContentType(ContentType.TEXT);
            feedback.setTextContent(feedbackText);
            feedback.setLabel(label);
            contentRepo.save(feedback);
        }

        if (assetUrls != null) {
            for (String url : assetUrls) {
                String blobPath = stripAssetPrefix(url);
                TrainingContent asset = new TrainingContent();
                asset.setId(UUID.randomUUID().toString());
                asset.setSessionId(session.getId());
                asset.setContentType(ContentType.ASSET);
                asset.setBlobPath(blobPath);
                asset.setFileName(blobPath.substring(blobPath.lastIndexOf('/') + 1));
                asset.setLabel(label);
                contentRepo.save(asset);
            }
        }

        return session;
    }

    public TrainingSession submitSession(String id) {
        TrainingSession session = getSession(id);
        if (session.getStatus() != Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT sessions can be submitted for review");
        }
        session.setStatus(Status.PENDING_REVIEW);
        session.setUpdatedAt(Instant.now());
        return sessionRepo.save(session);
    }

    public void approveSession(String id) {
        TrainingSession session = getSession(id);
        if (session.getStatus() != Status.PENDING_REVIEW) {
            throw new IllegalStateException("Only sessions pending review can be approved");
        }
        ingest(id);
    }

    public TrainingSession rejectSession(String id) {
        TrainingSession session = getSession(id);
        if (session.getStatus() != Status.PENDING_REVIEW) {
            throw new IllegalStateException("Only sessions pending review can be rejected");
        }
        session.setStatus(Status.REJECTED);
        session.setUpdatedAt(Instant.now());
        return sessionRepo.save(session);
    }

    private String stripAssetPrefix(String url) {
        int idx = url.indexOf("/api/assets/");
        return idx >= 0 ? url.substring(idx + "/api/assets/".length()) : url;
    }

    // ── Ingest ────────────────────────────────────────────────────────────────

    public void ingest(String sessionId) {
        TrainingSession session = getSession(sessionId);
        List<TrainingContent> contents = contentRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);

        if (session.getIntent() == Intent.FEEDBACK) {
            ingestFeedback(session, contents);
        } else {
            for (TrainingContent c : contents) {
                String text = resolveText(c);
                if (text == null || text.isBlank()) continue;

                Map<String, Object> payload = buildPayload(session, c, text);
                upsertToQdrant(text, payload, UUID.randomUUID().toString());
            }
        }

        session.setStatus(Status.INGESTED);
        session.setUpdatedAt(Instant.now());
        sessionRepo.save(session);
    }

    private void ingestFeedback(TrainingSession session, List<TrainingContent> contents) {
        StringBuilder text = new StringBuilder();
        List<String> assetPaths = new ArrayList<>();
        TrainingContent.Label label = null;

        for (TrainingContent c : contents) {
            if (label == null && c.getLabel() != null) label = c.getLabel();
            if (c.getContentType() == ContentType.ASSET) {
                if (c.getBlobPath() != null) assetPaths.add(c.getBlobPath());
                continue;
            }
            String t = resolveText(c);
            if (t != null && !t.isBlank()) text.append(t).append("\n\n");
        }
        if (text.isEmpty()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("text",        text.toString().strip());
        payload.put("source_type", "feedback");
        payload.put("session_id",  session.getId());
        payload.put("topic",       session.getTopic());
        payload.put("intent",      session.getIntent().name());
        payload.put("scope",       session.getScope().name());
        payload.put("asset_types", session.getAssetTypes() != null ? session.getAssetTypes() : "");
        payload.put("label",       label != null ? label.name() : "");
        payload.put("asset_paths", assetPaths);
        payload.put("layer",       "training");

        upsertToQdrant(text.toString().strip(), payload, UUID.randomUUID().toString());
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private String resolveText(TrainingContent c) {
        return switch (c.getContentType()) {
            case TEXT  -> c.getTextContent();
            case AUDIO -> c.getTextContent() != null ? c.getTextContent() : c.getTranscript();
            case FILE  -> null; // file text extraction not yet implemented — future: PDFBox
            case ASSET -> null; // image reference only — carried via asset_paths, not embedded as text
            case URL   -> c.getTextContent();
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
