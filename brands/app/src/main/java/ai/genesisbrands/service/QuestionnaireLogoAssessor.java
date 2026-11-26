package ai.genesisbrands.service;

import ai.genesisbrands.agent.logo.ImageTypes;
import ai.genesisbrands.agent.logo.LogoAssessment;
import ai.genesisbrands.agent.logo.LogoAudit;
import ai.genesisbrands.widget.logoassessment.ClientLogoAssessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Genesis Brands' side of the logoAssessment widget: finds the logo the client uploaded in
 * discovery ({@code look.currentLogo}), reads how they feel about it and what they think they want
 * to do with it, and runs the logo audit with the rest of their answers as brand context.
 */
@Component
public class QuestionnaireLogoAssessor implements ClientLogoAssessor {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireLogoAssessor.class);

    static final String LOGO_SIGNAL = "look.currentLogo";
    static final String FEELINGS_SIGNAL = "look.currentLogoFeelings";
    static final String INTENT_SIGNAL = "look.logoIntent";
    private static final String ASSET_PREFIX = "/api/assets/";
    /** Enough of the discovery answers to judge fit without drowning the image in text. */
    static final int CONTEXT_CHARS = 8000;
    private static final Set<String> NOT_CONTEXT = Set.of(LOGO_SIGNAL, FEELINGS_SIGNAL, INTENT_SIGNAL);

    private final LogoAudit audit;
    private final BlobStorageService blobs;
    private final ObjectMapper objectMapper;

    public QuestionnaireLogoAssessor(LogoAudit audit, BlobStorageService blobs, ObjectMapper objectMapper) {
        this.audit = audit;
        this.blobs = blobs;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result assess(Map<String, Object> signals) {
        List<String> urls = logoUrls(signals.get(LOGO_SIGNAL));
        if (urls.isEmpty()) return new Result(Status.NO_LOGO, null, null);

        // A client may upload the AI, the EPS and a PNG of the same logo; assess the first file
        // the audit can look at.
        for (String url : urls) {
            byte[] bytes;
            try {
                bytes = blobs.download(blobPath(url));
            } catch (IOException | RuntimeException e) {
                log.warn("Could not read uploaded logo {}: {}", url, e.getMessage());
                continue;
            }
            String type = ImageTypes.sniff(bytes);
            if (!LogoAudit.canView(type)) continue;
            LogoAssessment assessment = audit.assess(bytes, type, brandContext(signals),
                text(signals.get(FEELINGS_SIGNAL)), text(signals.get(INTENT_SIGNAL)));
            return new Result(Status.ASSESSED, url,
                objectMapper.convertValue(assessment, new TypeReference<Map<String, Object>>() {}));
        }
        return new Result(Status.UNVIEWABLE, urls.get(0), null);
    }

    /** The served URLs of an upload answer: a map with its blobPath, or a list of them. */
    static List<String> logoUrls(Object upload) {
        if (upload instanceof List<?> list) {
            return list.stream().flatMap(item -> logoUrls(item).stream()).toList();
        }
        if (upload instanceof Map<?, ?> m && m.get("blobPath") instanceof String path && !path.isBlank()) {
            return List.of(path);
        }
        return List.of();
    }

    /** The blob path behind a served /api/assets/ URL. */
    static String blobPath(String url) {
        return url.startsWith(ASSET_PREFIX) ? url.substring(ASSET_PREFIX.length()) : url;
    }

    /** The client's other answers as {@code key: value} lines, text-like values only. */
    static String brandContext(Map<String, Object> signals) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : signals.entrySet()) {
            if (NOT_CONTEXT.contains(e.getKey())) continue;
            String value = text(e.getValue());
            if (value == null) continue;
            String line = e.getKey() + ": " + value + "\n";
            if (sb.length() + line.length() > CONTEXT_CHARS) break;
            sb.append(line);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** A string, number or list of those as text; null for uploads, maps and blanks. */
    static String text(Object value) {
        if (value instanceof String s) return s.isBlank() ? null : s.strip();
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof List<?> list) {
            List<String> parts = list.stream().map(QuestionnaireLogoAssessor::text).filter(p -> p != null).toList();
            return parts.isEmpty() ? null : String.join("; ", parts);
        }
        return null;
    }
}
