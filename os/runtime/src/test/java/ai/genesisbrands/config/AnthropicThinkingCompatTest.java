package ai.genesisbrands.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicThinkingCompatTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> MODELS = List.of("claude-opus-5-5");

    private static byte[] b(String json) { return json.getBytes(StandardCharsets.UTF_8); }

    @Test
    void listedModelWithDisabledThinkingBecomesAdaptiveAtLowEffort() throws Exception {
        byte[] in = b("{\"model\":\"claude-opus-5-5\",\"max_tokens\":10,\"thinking\":{\"type\":\"disabled\"}}");
        JsonNode out = M.readTree(AnthropicThinkingCompat.rewrite(in, MODELS, "low"));
        assertThat(out.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(out.path("thinking").path("display").asText()).isEqualTo("omitted");
        assertThat(out.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(out.path("max_tokens").asInt()).isEqualTo(10);
    }

    @Test
    void otherModelsAreSentUnchanged() {
        byte[] in = b("{\"model\":\"claude-opus-5\",\"thinking\":{\"type\":\"disabled\"}}");
        assertThat(AnthropicThinkingCompat.rewrite(in, MODELS, "low")).isSameAs(in);
    }

    @Test
    void anExplicitEffortOrNonDisabledThinkingIsRespected() throws Exception {
        byte[] withEffort = b("{\"model\":\"claude-opus-5-5\",\"thinking\":{\"type\":\"disabled\"},\"output_config\":{\"effort\":\"high\"}}");
        assertThat(M.readTree(AnthropicThinkingCompat.rewrite(withEffort, MODELS, "low")).path("output_config").path("effort").asText()).isEqualTo("high");
        byte[] adaptive = b("{\"model\":\"claude-opus-5-5\",\"thinking\":{\"type\":\"adaptive\"}}");
        assertThat(AnthropicThinkingCompat.rewrite(adaptive, MODELS, "low")).isSameAs(adaptive);
    }

    @Test
    void nonJsonBodiesPassThrough() {
        byte[] in = b("not json");
        assertThat(AnthropicThinkingCompat.rewrite(in, MODELS, "low")).isSameAs(in);
    }

    // Sends real requests through both HTTP paths Spring AI uses (blocking RestClient, streaming
    // WebClient) to a local server, to prove the rewritten body is what arrives, with a matching length.
    @Test
    void bothHttpPathsSendTheRewrittenBody() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> length = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            body.set(new String(in, StandardCharsets.UTF_8));
            length.set(ex.getRequestHeaders().getFirst("Content-Length"));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
            Map<String, Object> request = Map.of("model", "claude-opus-5-5", "thinking", Map.of("type", "disabled"));
            AnthropicThinkingCompat compat = new AnthropicThinkingCompat("claude-opus-5-5", "low");

            RestClient.Builder rb = RestClient.builder();
            compat.anthropicThinkingRestCustomizer().customize(rb);
            rb.build().post().uri(url).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(request).retrieve().toBodilessEntity();
            assertSent(body, length);

            body.set(null);
            WebClient.Builder wb = WebClient.builder();
            compat.anthropicThinkingWebCustomizer().customize(wb);
            wb.build().post().uri(url).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Mono.just(request), Map.class).retrieve().toBodilessEntity().block();
            assertSent(body, length);
        } finally {
            server.stop(0);
        }
    }

    private static void assertSent(AtomicReference<String> body, AtomicReference<String> length) throws Exception {
        JsonNode sent = M.readTree(body.get());
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(sent.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(length.get()).isEqualTo(String.valueOf(body.get().getBytes(StandardCharsets.UTF_8).length));
    }
}
