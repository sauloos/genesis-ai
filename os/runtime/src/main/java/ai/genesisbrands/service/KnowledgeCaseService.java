package ai.genesisbrands.service;

import ai.genesisbrands.model.KnowledgeCase;
import ai.genesisbrands.platform.knowledge.KnowledgeCases;
import ai.genesisbrands.repository.KnowledgeCaseRepository;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * The case library: admins add real examples marked GOOD or BAD, a drafter (an Opus call that
 * reads the image against the Layer 1 modules for that kind of work) writes the analysis and the
 * lesson, the admin edits and approves, and agents read approved cases through the
 * {@link KnowledgeCases} port. Images live in blob storage, never in the database.
 */
@Service
public class KnowledgeCaseService implements KnowledgeCases {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCaseService.class);
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final String PROMPT = "knowledge-cases/drafter.md";

    private final KnowledgeCaseRepository repository;
    private final BlobStorageService blobStorage;
    private final Layer1Service layer1Service;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;

    @Value("${genesis.knowledge.cases.model:claude-opus-5}")
    private String model = "claude-opus-5";

    @Value("${genesis.knowledge.cases.max-tokens:3000}")
    private int maxTokens = 3000;

    public KnowledgeCaseService(KnowledgeCaseRepository repository, BlobStorageService blobStorage,
                                Layer1Service layer1Service, ObjectMapper objectMapper,
                                ChatClient.Builder chatClientBuilder) {
        this.repository = repository;
        this.blobStorage = blobStorage;
        this.layer1Service = layer1Service;
        this.objectMapper = objectMapper;
        this.chatClient = chatClientBuilder.build();
    }

    // ── Admin operations ─────────────────────────────────────────────────────

    public List<KnowledgeCase> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<KnowledgeCase> get(String id) {
        return repository.findById(id);
    }

    /** Saves a new DRAFT case with its image, then drafts its analysis (kept even if drafting fails). */
    public KnowledgeCase create(String kind, String verdict, String note, String sector, String sourceUrl,
                                byte[] image, String imageType) throws IOException {
        KnowledgeCase c = new KnowledgeCase();
        c.setId(UUID.randomUUID().toString());
        c.setKind(normaliseKind(kind));
        c.setVerdict(normaliseVerdict(verdict));
        c.setNote(blankToNull(note));
        c.setSector(blankToNull(sector));
        c.setSourceUrl(blankToNull(sourceUrl));
        if (image != null && image.length > 0) {
            String type = sniff(image);
            if (type == null || !IMAGE_TYPES.contains(type)) {
                throw new IllegalArgumentException("Upload a PNG, JPEG, WebP or GIF image.");
            }
            if (image.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("The image is larger than 5 MB.");
            }
            String path = "knowledge-cases/" + c.getId() + extension(type);
            blobStorage.upload(path, image);
            c.setImagePath(path);
            c.setImageType(type);
        } else if (c.getNote() == null) {
            throw new IllegalArgumentException("Add an image or a note.");
        }
        repository.save(c);
        return draft(c);
    }

    /** Re-runs the drafter; the admin's edits to title, analysis and lesson are replaced. */
    public KnowledgeCase redraft(String id) {
        return draft(repository.findById(id).orElseThrow(() -> new NoSuchElementException(id)));
    }

    public KnowledgeCase update(String id, Map<String, Object> patch) {
        KnowledgeCase c = repository.findById(id).orElseThrow(() -> new NoSuchElementException(id));
        if (patch.containsKey("title")) c.setTitle(blankToNull(str(patch.get("title"))));
        if (patch.containsKey("sector")) c.setSector(blankToNull(str(patch.get("sector"))));
        if (patch.containsKey("tags")) c.setTags(normaliseTags(patch.get("tags")));
        if (patch.containsKey("note")) c.setNote(blankToNull(str(patch.get("note"))));
        if (patch.containsKey("analysis")) c.setAnalysis(blankToNull(str(patch.get("analysis"))));
        if (patch.containsKey("lesson")) c.setLesson(blankToNull(str(patch.get("lesson"))));
        if (patch.containsKey("verdict")) c.setVerdict(normaliseVerdict(str(patch.get("verdict"))));
        if (patch.containsKey("kind")) c.setKind(normaliseKind(str(patch.get("kind"))));
        if (patch.containsKey("status")) {
            String status = str(patch.get("status")).toUpperCase(Locale.ROOT);
            if (!status.equals(KnowledgeCase.DRAFT) && !status.equals(KnowledgeCase.APPROVED)) {
                throw new IllegalArgumentException("Status is DRAFT or APPROVED.");
            }
            if (status.equals(KnowledgeCase.APPROVED) && (c.getLesson() == null || c.getAnalysis() == null)) {
                throw new IllegalArgumentException("A case needs an analysis and a lesson before it is approved.");
            }
            c.setStatus(status);
        }
        c.setUpdatedAt(Instant.now());
        return repository.save(c);
    }

    public void delete(String id) {
        repository.findById(id).ifPresent(c -> {
            if (c.getImagePath() != null) blobStorage.delete(c.getImagePath());
            repository.delete(c);
        });
    }

    public byte[] image(KnowledgeCase c) throws IOException {
        return blobStorage.download(c.getImagePath());
    }

    // ── The port agents read ─────────────────────────────────────────────────

    @Override
    public List<Case> approved(String kind, String hint, int limit) {
        if (limit <= 0) return List.of();
        Set<String> words = words(hint);
        return repository.findByKindAndStatusOrderByCreatedAtDesc(normaliseKind(kind), KnowledgeCase.APPROVED)
            .stream()
            .sorted(Comparator.comparingInt((KnowledgeCase c) -> -overlap(c, words)))
            .limit(limit)
            .map(c -> new Case(c.getVerdict(), c.getTitle(), c.getSector(), tagList(c.getTags()),
                c.getAnalysis(), c.getLesson()))
            .toList();
    }

    /** How many of the hint's words the case's sector and tags share; ties keep newest first. */
    static int overlap(KnowledgeCase c, Set<String> words) {
        if (words.isEmpty()) return 0;
        Set<String> own = words((c.getSector() == null ? "" : c.getSector()) + " "
            + (c.getTags() == null ? "" : c.getTags().replace('_', ' ')));
        own.retainAll(words);
        return own.size();
    }

    static Set<String> words(String text) {
        if (text == null) return new HashSet<>();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(w -> w.length() > 2)
            .collect(Collectors.toCollection(HashSet::new));
    }

    // ── Drafting ─────────────────────────────────────────────────────────────

    private KnowledgeCase draft(KnowledgeCase c) {
        try {
            byte[] image = c.getImagePath() == null ? null : blobStorage.download(c.getImagePath());
            String user = draftInput(c);
            String raw = chatClient.prompt()
                .system(prompt())
                .user(u -> {
                    u.text(user);
                    if (image != null) {
                        u.media(MimeType.valueOf(c.getImageType()), new ByteArrayResource(image));
                    }
                })
                .options(AnthropicChatOptions.builder().model(model).maxTokens(maxTokens).build())
                .call()
                .content();
            applyDraft(c, raw);
        } catch (Exception e) {
            log.warn("Drafting knowledge case {} failed: {}", c.getId(), e.getMessage());
            if (c.getLesson() == null && c.getNote() != null) c.setLesson(c.getNote());
        }
        c.setUpdatedAt(Instant.now());
        return repository.save(c);
    }

    String draftInput(KnowledgeCase c) {
        StringBuilder sb = new StringBuilder();
        sb.append("## The case\n\n");
        sb.append("Kind of work: ").append(c.getKind()).append('\n');
        sb.append("Admin's verdict: ").append(c.getVerdict()).append('\n');
        if (c.getNote() != null) sb.append("Admin's note: ").append(c.getNote()).append('\n');
        if (c.getSector() != null) sb.append("Sector: ").append(c.getSector()).append('\n');
        if (c.getSourceUrl() != null) sb.append("Source: ").append(c.getSourceUrl()).append('\n');
        sb.append(c.getImagePath() == null ? "\nThere is no image; work from the note.\n" : "\nThe image is attached.\n");
        String modules = modulesFor(c.getKind());
        if (!modules.isEmpty()) {
            sb.append("\n## The agency's Layer 1 modules for this kind of work\n\n").append(modules);
        }
        return sb.toString();
    }

    /** The Layer 1 modules whose file name names this kind, e.g. logo-craft.yaml and logo-rubric.yaml. */
    String modulesFor(String kind) {
        StringBuilder sb = new StringBuilder();
        layer1Service.listModules().forEach((key, content) -> {
            String file = key.substring(key.lastIndexOf('/') + 1);
            if (file.startsWith(kind + "-") || file.startsWith(kind + ".")) {
                sb.append("### ").append(key).append("\n\n").append(content).append("\n\n");
            }
        });
        return sb.toString();
    }

    void applyDraft(KnowledgeCase c, String raw) throws IOException {
        JsonNode n = objectMapper.reader().with(JsonReadFeature.ALLOW_TRAILING_COMMA).readTree(json(raw));
        c.setTitle(text(n, "title", c.getTitle()));
        if (c.getSector() == null) c.setSector(text(n, "sector", null));
        if (n.path("tags").isArray()) {
            c.setTags(normaliseTags(StreamSupport.stream(n.path("tags").spliterator(), false)
                .map(JsonNode::asText).toList()));
        }
        c.setAnalysis(text(n, "analysis", c.getAnalysis()));
        c.setLesson(text(n, "lesson", c.getLesson()));
    }

    private String prompt() throws IOException {
        return new ClassPathResource(PROMPT).getContentAsString(StandardCharsets.UTF_8);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static String json(String raw) {
        if (raw == null) throw new IllegalStateException("The drafter returned nothing.");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("The drafter returned no JSON.");
        return raw.substring(start, end + 1);
    }

    private static String text(JsonNode n, String field, String fallback) {
        String v = n.path(field).asText("").strip();
        return v.isEmpty() ? fallback : v;
    }

    static String normaliseVerdict(String verdict) {
        String v = verdict == null ? "" : verdict.strip().toUpperCase(Locale.ROOT);
        if (!v.equals(KnowledgeCase.GOOD) && !v.equals(KnowledgeCase.BAD)) {
            throw new IllegalArgumentException("Mark the case GOOD or BAD.");
        }
        return v;
    }

    static String normaliseKind(String kind) {
        String k = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
        if (k.isEmpty()) throw new IllegalArgumentException("Say what kind of work the case is, e.g. logo.");
        return k;
    }

    static String normaliseTags(Object tags) {
        Collection<?> items = tags instanceof Collection<?> col ? col
            : Arrays.asList(String.valueOf(tags == null ? "" : tags).split(","));
        String joined = items.stream().map(t -> String.valueOf(t).strip().toLowerCase(Locale.ROOT))
            .filter(t -> !t.isEmpty()).distinct().collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined.length() > 500 ? joined.substring(0, 500) : joined;
    }

    static List<String> tagList(String tags) {
        return tags == null || tags.isBlank() ? List.of() : List.of(tags.split(","));
    }

    /** The image type from its first bytes, so a mislabelled upload is never served as something else. */
    static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static String extension(String type) {
        return switch (type) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
