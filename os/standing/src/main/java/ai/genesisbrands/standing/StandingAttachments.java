package ai.genesisbrands.standing;

import ai.genesisbrands.platform.standing.StandingRunResult;
import ai.genesisbrands.service.BlobStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps the files a standing run produced (a post's images) in blob storage, under the
 * client's own prefix, and reads them back for that client only. The inbox row holds just the
 * list of what was stored.
 */
@Slf4j
@Service
public class StandingAttachments {

    static final int MAX_PER_ITEM = 10;
    static final int MAX_BYTES = 15 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "text/plain", "text/markdown");

    /** One stored file: what the client sees, and where its bytes live. */
    public record Stored(String name, String contentType, long size, String blobPath) {}

    private final BlobStorageService blobs;
    private final ObjectMapper objectMapper;

    public StandingAttachments(BlobStorageService blobs, ObjectMapper objectMapper) {
        this.blobs = blobs;
        this.objectMapper = objectMapper;
    }

    /** Stores what the agent attached; a file that is too big or of an unexpected type is dropped. */
    public List<Stored> store(String clientUserId, String itemId, List<StandingRunResult.Attachment> attachments) {
        List<Stored> out = new ArrayList<>();
        if (attachments == null) return out;
        for (StandingRunResult.Attachment a : attachments) {
            if (out.size() >= MAX_PER_ITEM) break;
            if (a == null || a.data() == null || a.data().length == 0) continue;
            String type = a.contentType() == null ? "" : a.contentType().toLowerCase(Locale.ROOT);
            if (!ALLOWED_TYPES.contains(type) || a.data().length > MAX_BYTES) {
                log.warn("Dropped attachment '{}' ({}, {} bytes) of inbox item {}", a.name(), type, a.data().length, itemId);
                continue;
            }
            String name = safeName(a.name(), out.size() + 1, type);
            String path = "standing/" + clientUserId + "/" + itemId + "/" + out.size() + "-" + name;
            try {
                blobs.upload(path, a.data());
                out.add(new Stored(name, type, a.data().length, path));
            } catch (IOException e) {
                log.warn("Could not store attachment '{}' of inbox item {}: {}", name, itemId, e.getMessage());
            }
        }
        return out;
    }

    public List<Stored> list(InboxItem item) {
        if (item.getAttachmentsJson() == null || item.getAttachmentsJson().isBlank()) return List.of();
        try {
            return objectMapper.readValue(item.getAttachmentsJson(), new TypeReference<List<Stored>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public String toJson(List<Stored> stored) {
        if (stored.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] read(Stored stored) throws IOException {
        return blobs.download(stored.blobPath());
    }

    static String safeName(String name, int n, String type) {
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-{2,}", "-");
        cleaned = cleaned.replaceAll("^[.-]+", "");
        if (cleaned.length() > 80) cleaned = cleaned.substring(cleaned.length() - 80);
        if (cleaned.isBlank()) cleaned = "file-" + n + extension(type);
        return cleaned;
    }

    private static String extension(String type) {
        return switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "application/pdf" -> ".pdf";
            case "text/markdown" -> ".md";
            default -> ".txt";
        };
    }
}
