package ai.genesisbrands.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct calls to the OpenAI images API (or an Azure OpenAI resource's v1 API), for what Spring
 * AI's {@code ImageModel} cannot ask for: a transparent background, several candidates in one
 * request, and edits that take a source image.
 *
 * <p>It reads the same settings the Spring AI image client does
 * ({@code spring.ai.openai.image.base-url}/{@code api-key}, falling back to
 * {@code spring.ai.openai.*}), so moving images to Azure moves these calls too. The model id is
 * {@link ImageModelProperties#getModel()}.
 *
 * <p>A 429 or 5xx is retried with backoff (or the server's Retry-After, if longer): the Azure
 * deployment allows two requests a minute and three directions draw their logos at the same time.
 */
@Component
public class OpenAiImageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/";
    private static final int MAX_ATTEMPTS = 8;

    private final ImageModelProperties imageModels;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public OpenAiImageClient(ImageModelProperties imageModels,
                             ObjectMapper objectMapper,
                             @Value("${spring.ai.openai.image.base-url:}") String imageBaseUrl,
                             @Value("${spring.ai.openai.base-url:}") String baseUrl,
                             @Value("${spring.ai.openai.image.api-key:}") String imageApiKey,
                             @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.imageModels = imageModels;
        this.objectMapper = objectMapper;
        String base = firstNonBlank(imageBaseUrl, baseUrl, DEFAULT_BASE_URL);
        this.baseUrl = base.endsWith("/") ? base : base + "/";
        this.apiKey = firstNonBlank(imageApiKey, apiKey, "");
        // A high-quality image takes 30-60s, several in one request longer still.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
        factory.setReadTimeout(Duration.ofMinutes(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** What to ask for. {@code background} is {@code transparent}, {@code opaque} or {@code auto}. */
    public record Request(String prompt, int n, String quality, String size, String background) {
        public static Request logo(String prompt, int n) {
            return new Request(prompt, n, "high", "1024x1024", "transparent");
        }
    }

    public String model() { return imageModels.getModel(); }

    /** Generates {@code n} images; returns their PNG bytes. */
    public List<byte[]> generate(Request req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("prompt", req.prompt());
        body.put("n", req.n());
        body.put("size", req.size());
        body.put("quality", req.quality());
        body.put("background", req.background());
        body.put("output_format", "png");
        return call("v1/images/generations", () -> restClient.post()
            .uri(baseUrl + "v1/images/generations")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class));
    }

    /** Edits {@code source} per the prompt (the model redraws it; it is not a pixel edit). */
    public List<byte[]> edit(byte[] source, Request req) {
        return call("v1/images/edits", () -> {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("model", model());
            form.add("prompt", req.prompt());
            form.add("n", String.valueOf(req.n()));
            form.add("size", req.size());
            form.add("quality", req.quality());
            form.add("background", req.background());
            form.add("output_format", "png");
            form.add("image[]", new ByteArrayResource(source) {
                @Override public String getFilename() { return "source.png"; }
            });
            return restClient.post()
                .uri(baseUrl + "v1/images/edits")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);
        });
    }

    private List<byte[]> call(String path, java.util.function.Supplier<String> request) {
        long backoffMs = 10_000;
        for (int attempt = 1; ; attempt++) {
            try {
                return decode(request.get());
            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                boolean retryable = status.value() == 429 || status.is5xxServerError();
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("Image call " + path + " failed (" + model() + "): "
                        + status.value() + " " + abbreviate(e.getResponseBodyAsString()), e);
                }
                long waitMs = Math.max(backoffMs, retryAfterMs(e));
                log.info("Image call {} got {} (attempt {}/{}); retrying in {}s",
                    path, status.value(), attempt, MAX_ATTEMPTS, waitMs / 1000);
                sleep(waitMs);
                backoffMs = Math.min(backoffMs * 2, 60_000);
            }
        }
    }

    private List<byte[]> decode(String json) {
        try {
            JsonNode data = objectMapper.readTree(json).path("data");
            List<byte[]> images = new ArrayList<>();
            for (JsonNode item : data) {
                String b64 = item.path("b64_json").asText(null);
                if (b64 != null) images.add(Base64.getDecoder().decode(b64));
            }
            if (images.isEmpty()) throw new IllegalStateException("Image response held no images");
            return images;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable image response: " + e.getMessage(), e);
        }
    }

    /** The server's own Retry-After (seconds), capped at two minutes; 0 when absent. */
    private static long retryAfterMs(RestClientResponseException e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (header == null) return 0;
        try {
            return Math.min(Long.parseLong(header.trim()), 120) * 1000;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry an image call", ie);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
