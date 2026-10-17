package ai.genesisbrands.connector.platform;

import ai.genesisbrands.connector.auth.ConnectorPrincipal;
import ai.genesisbrands.platform.connector.PlatformOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Runs a Platform connector operation by calling the app's own admin REST handler over
 * loopback with the admin credentials — so every operation goes through exactly the same
 * controller, validation and filters as the admin dashboard, with no second code path to keep
 * in sync. Every call is written to the platform audit log against the calling key.
 */
@Service
@RequiredArgsConstructor
public class PlatformConnectorService {

    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_TEXT_RESPONSE = 200_000;

    private final PlatformOperationCatalog catalog;
    private final PlatformAuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${genesis.basic-auth.username}")
    private String adminUsername;

    @Value("${genesis.basic-auth.password}")
    private String adminPassword;

    @Value("${genesis.api-key}")
    private String apiKey;

    public List<PlatformOperation> list(String query) {
        return catalog.list(query);
    }

    public PlatformOperation describe(String operationId) {
        return catalog.find(operationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown operation: " + operationId));
    }

    public List<PlatformAuditEntry> audit(int limit) {
        return auditRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(200, limit))));
    }

    public record CallRequest(Map<String, String> pathParams, Map<String, Object> query, JsonNode body) {}

    public record CallResult(String operationId, String method, String path, int status, String contentType, Object body) {}

    public CallResult call(String operationId, CallRequest request, ConnectorPrincipal principal) {
        PlatformOperation op = describe(operationId);
        CallRequest req = request == null ? new CallRequest(null, null, null) : request;
        String path = resolvePath(op, req.pathParams() == null ? Map.of() : req.pathParams());
        String uri = path + queryString(op, req.query() == null ? Map.of() : req.query());

        PlatformAuditEntry audit = new PlatformAuditEntry();
        audit.setId(UUID.randomUUID().toString());
        audit.setKeyId(principal.keyId());
        audit.setKeyLabel(principal.keyLabel());
        audit.setOperationId(op.id());
        audit.setMethod(op.method());
        audit.setPath(truncate(uri, 1000));
        audit.setCreatedAt(Instant.now());

        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> response = httpClient.send(buildRequest(op, uri, req.body()), HttpResponse.BodyHandlers.ofByteArray());
            audit.setStatus(response.statusCode());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new CallResult(op.id(), op.method(), uri, response.statusCode(), contentType, readBody(response.body(), contentType));
        } catch (IOException e) {
            audit.setError(truncate("I/O error: " + e.getMessage(), 500));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The operation could not be completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audit.setError("Interrupted");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The operation was interrupted");
        } finally {
            audit.setDurationMs((System.nanoTime() - started) / 1_000_000);
            auditRepository.save(audit);
        }
    }

    private HttpRequest buildRequest(PlatformOperation op, String uri, JsonNode body) {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String credentials = Base64.getEncoder().encodeToString((adminUsername + ":" + adminPassword).getBytes(StandardCharsets.UTF_8));
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null && !body.isNull()) {
            if (!op.acceptsBody()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operation '" + op.id() + "' takes no body");
            }
            try {
                publisher = HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body));
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body is not valid JSON");
            }
        }
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + uri))
            .timeout(CALL_TIMEOUT)
            .header("Authorization", "Basic " + credentials)
            .header("X-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .method(op.method(), publisher)
            .build();
    }

    /** Fills the URI template. Each value is one encoded path segment — it can't add segments or climb out. */
    private static String resolvePath(PlatformOperation op, Map<String, String> values) {
        String path = op.path();
        for (String name : op.pathParams()) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing path parameter '" + name + "'");
            }
            if (value.equals(".") || value.equals("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path parameter '" + name + "'");
            }
            path = path.replaceFirst("\\{" + java.util.regex.Pattern.quote(name) + "(?::[^}]*)?}",
                java.util.regex.Matcher.quoteReplacement(encode(value)));
        }
        Set<String> unknown = new TreeSet<>(values.keySet());
        op.pathParams().forEach(unknown::remove);
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown path parameter(s): " + String.join(", ", unknown));
        }
        return path;
    }

    private static String queryString(PlatformOperation op, Map<String, Object> query) {
        if (query.isEmpty()) return "";
        StringJoiner joiner = new StringJoiner("&", "?", "");
        for (Map.Entry<String, Object> e : query.entrySet()) {
            if (!op.queryParams().contains(e.getKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Operation '" + op.id() + "' has no query parameter '" + e.getKey() + "'");
            }
            Collection<?> values = e.getValue() instanceof Collection<?> c ? c : Collections.singletonList(e.getValue());
            for (Object v : values) {
                if (v != null) joiner.add(encode(e.getKey()) + "=" + encode(String.valueOf(v)));
            }
        }
        return joiner.toString();
    }

    private Object readBody(byte[] bytes, String contentType) {
        if (bytes.length == 0) return null;
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("json")) {
            try {
                return objectMapper.readTree(bytes);
            } catch (IOException ignored) {
                // fall through to text
            }
        }
        if (type.isEmpty() || type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() > MAX_TEXT_RESPONSE ? text.substring(0, MAX_TEXT_RESPONSE) + "\n…[truncated]" : text;
        }
        return "[binary content: " + bytes.length + " bytes, " + contentType + "]";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
