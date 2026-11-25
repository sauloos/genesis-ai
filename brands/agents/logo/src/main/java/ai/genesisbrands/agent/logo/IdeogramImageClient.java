package ai.genesisbrands.agent.logo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
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
import java.util.List;

/**
 * Ideogram's v2 image API (developer.ideogram.ai). The model is in the path
 * ({@code /v2/image/generate/<model>}, default {@code ideogram-4-6}); the body is multipart, and
 * the same endpoint edits when source {@code images} are attached. Image links expire after 24
 * hours, so each one is downloaded straight away.
 *
 * <p>Ideogram limits images in flight per organisation (10 by default) and answers 429 with
 * {@code reject_reason: inflight_limit} without running or billing the request, so a 429 is safe
 * to retry. A client timeout is not: the request may still run and be billed.
 */
@Component
public class IdeogramImageClient {

    private static final Logger log = LoggerFactory.getLogger(IdeogramImageClient.class);
    private static final String BASE_URL = "https://api.ideogram.ai/v2/image/generate/";
    private static final int MAX_ATTEMPTS = 5;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Value("${ideogram.api-key:}")
    private String apiKey;

    /** Ideogram model id as it appears in the path, e.g. {@code ideogram-4-6}. */
    @Value("${ideogram.model:ideogram-4-6}")
    private String model;

    /** {@code very_low}, {@code low}, {@code medium} or {@code high}. */
    @Value("${ideogram.quality:high}")
    private String quality;

    public IdeogramImageClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
        // Longer than the API's 265s synchronous wait, as Ideogram's docs advise.
        factory.setReadTimeout(Duration.ofSeconds(300));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** The id the usage ledger prices this model by. */
    public String model() { return model; }

    public List<byte[]> generate(String prompt, int n) {
        return call(form(prompt, n), null);
    }

    public List<byte[]> edit(byte[] source, String prompt) {
        return call(form(prompt, 1), source);
    }

    private MultiValueMap<String, Object> form(String prompt, int n) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("prompt", prompt);
        form.add("num_images", String.valueOf(n));
        form.add("quality", quality);
        form.add("size", "1024x1024");
        form.add("background", "transparent");
        // The agent already writes a full art-direction prompt; Ideogram's rewrite would dilute it.
        form.add("magic_prompt", "off");
        return form;
    }

    private List<byte[]> call(MultiValueMap<String, Object> form, byte[] source) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Ideogram API key not configured (set IDEOGRAM_API_KEY)");
        }
        if (source != null) {
            form.add("images", new ByteArrayResource(source) {
                @Override public String getFilename() { return "source.png"; }
            });
        }
        long backoffMs = 5_000;
        for (int attempt = 1; ; attempt++) {
            try {
                String json = restClient.post()
                    .uri(BASE_URL + model)
                    .header("Api-Key", apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);
                return download(json);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != 429 || attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("Ideogram " + model + " failed: "
                        + e.getStatusCode().value() + " " + e.getResponseBodyAsString(), e);
                }
                log.info("Ideogram busy (attempt {}/{}); retrying in {}s", attempt, MAX_ATTEMPTS, backoffMs / 1000);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting to retry Ideogram", ie);
                }
                backoffMs = Math.min(backoffMs * 2, 60_000);
            }
        }
    }

    private List<byte[]> download(String json) {
        try {
            List<byte[]> images = new ArrayList<>();
            for (JsonNode item : objectMapper.readTree(json).path("data")) {
                String url = item.path("url").asText("");
                if (!item.path("is_image_safe").asBoolean(true) || url.isBlank()) continue;
                byte[] bytes = restClient.get().uri(java.net.URI.create(url)).retrieve().body(byte[].class);
                if (bytes != null && bytes.length > 0) images.add(bytes);
            }
            if (images.isEmpty()) throw new IllegalStateException("Ideogram returned no usable images");
            return images;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable Ideogram response: " + e.getMessage(), e);
        }
    }
}
