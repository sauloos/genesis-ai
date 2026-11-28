package ai.genesisbrands.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Lets newer Claude models work with a Spring AI that predates them.
 *
 * <p>The runtime sends {@code thinking: {type: disabled}} on every call (see
 * {@link AiModelConfig}), and Spring AI 1.1.7 cannot express anything else. Claude Opus 5.5
 * rejects that with a 400 and asks for {@code thinking.type=adaptive} plus
 * {@code output_config.effort}. For the models listed in
 * {@code genesis.anthropic.adaptive-thinking-models}, this rewrites the outgoing request body
 * (blocking and streaming paths) to adaptive thinking at the configured effort. Verified
 * against the live API: adaptive + effort {@code low} answers with 0 thinking tokens on a
 * simple question. Every other model's request is left untouched.
 */
@Configuration
public class AnthropicThinkingCompat {

    private static final Logger log = LoggerFactory.getLogger(AnthropicThinkingCompat.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> models;
    private final String effort;

    public AnthropicThinkingCompat(
            @Value("${genesis.anthropic.adaptive-thinking-models:claude-opus-5-5}") String models,
            @Value("${genesis.anthropic.adaptive-effort:low}") String effort) {
        this.models = Arrays.stream(models.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.effort = effort;
    }

    /** Returns the body to send: rewritten when it targets a listed model with thinking disabled, else the same bytes. */
    static byte[] rewrite(byte[] body, List<String> models, String effort) {
        if (body == null || body.length == 0 || models.isEmpty()) return body;
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!(root instanceof ObjectNode obj)) return body;
            String model = obj.path("model").asText("");
            if (models.stream().noneMatch(model::startsWith)) return body;
            if (!"disabled".equals(obj.path("thinking").path("type").asText())) return body;
            obj.set("thinking", MAPPER.createObjectNode().put("type", "adaptive"));
            JsonNode existing = obj.get("output_config");
            ObjectNode cfg = existing instanceof ObjectNode o ? o : obj.putObject("output_config");
            if (!cfg.has("effort")) cfg.put("effort", effort);
            return MAPPER.writeValueAsBytes(obj);
        } catch (IOException e) {
            log.warn("Could not inspect an Anthropic request for adaptive thinking; sending it unchanged", e);
            return body;
        }
    }

    // Only the Anthropic messages endpoint is touched; the app's other HTTP clients pass through.
    private static boolean isMessagesCall(String path) {
        return path != null && path.endsWith("/v1/messages");
    }

    @Bean
    public RestClientCustomizer anthropicThinkingRestCustomizer() {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            if (!isMessagesCall(request.getURI().getPath())) return execution.execute(request, body);
            byte[] out = rewrite(body, models, effort);
            if (out != body) request.getHeaders().setContentLength(out.length);
            return execution.execute(request, out);
        };
        return builder -> builder.requestInterceptor(interceptor);
    }

    @Bean
    public WebClientCustomizer anthropicThinkingWebCustomizer() {
        ExchangeFilterFunction filter = (request, next) -> {
            if (!request.method().matches("POST") || !isMessagesCall(request.url().getPath())) return next.exchange(request);
            BodyInserter<?, ? super ClientHttpRequest> original = request.body();
            BodyInserter<?, ClientHttpRequest> rewriting = (message, context) ->
                original.insert(new ClientHttpRequestDecorator(message) {
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        return DataBufferUtils.join(body).flatMap(joined -> {
                            byte[] in = new byte[joined.readableByteCount()];
                            joined.read(in);
                            DataBufferUtils.release(joined);
                            byte[] out = rewrite(in, models, effort);
                            getHeaders().setContentLength(out.length);
                            return super.writeWith(Mono.just(bufferFactory().wrap(out)));
                        });
                    }
                }, context);
            return next.exchange(ClientRequest.from(request).body(rewriting).build());
        };
        return builder -> builder.filter(filter);
    }
}
