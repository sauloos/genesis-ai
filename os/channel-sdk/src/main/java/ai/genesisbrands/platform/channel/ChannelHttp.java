package ai.genesisbrands.platform.channel;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The small HTTP surface a channel needs, over the JDK client. Tests pass their own
 * transport to record requests and script responses. Network failures surface as retryable
 * {@link ChannelException}s; status codes are left to the channel, which knows what they mean.
 */
public final class ChannelHttp {

    public record Request(String method, String url, Map<String, String> headers, byte[] body) {}

    public record Response(int status, String body, Map<String, List<String>> headers) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public Optional<String> header(String name) {
            return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty())
                .map(e -> e.getValue().get(0)).findFirst();
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Function<Request, Response> transport;

    public ChannelHttp() {
        this(jdk());
    }

    public ChannelHttp(Function<Request, Response> transport) {
        this.transport = transport;
    }

    public Response get(String url, Map<String, String> headers) {
        return transport.apply(new Request("GET", url, headers, null));
    }

    public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        return transport.apply(new Request("POST", url, h, query(form).getBytes(StandardCharsets.UTF_8)));
    }

    public Response postJson(String url, String json, Map<String, String> headers) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Content-Type", "application/json");
        return transport.apply(new Request("POST", url, h, json.getBytes(StandardCharsets.UTF_8)));
    }

    public Response put(String url, byte[] body, String contentType, Map<String, String> headers) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Content-Type", contentType);
        return transport.apply(new Request("PUT", url, h, body));
    }

    /** {@code a=1&b=2}, each value URL-encoded; null values are left out. */
    public static String query(Map<String, String> params) {
        return params.entrySet().stream()
            .filter(e -> e.getValue() != null)
            .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
            .collect(Collectors.joining("&"));
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Function<Request, Response> jdk() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        return req -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(req.url())).timeout(TIMEOUT);
            req.headers().forEach(b::header);
            b.method(req.method(), req.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(req.body()));
            try {
                HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return new Response(res.statusCode(), res.body(), res.headers().map());
            } catch (IOException e) {
                throw ChannelException.retryable("Could not reach the platform: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ChannelException.retryable("Interrupted while calling the platform.");
            }
        };
    }
}
