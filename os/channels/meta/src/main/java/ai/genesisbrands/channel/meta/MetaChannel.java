package ai.genesisbrands.channel.meta;

import ai.genesisbrands.platform.channel.ChannelException;
import ai.genesisbrands.platform.channel.ChannelHttp;
import ai.genesisbrands.platform.channel.PublishChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facebook Pages and Instagram professional accounts, through one Facebook Login. The client
 * picks Pages on Meta's consent screen; each Page becomes a Facebook account, and each linked
 * Instagram account becomes an Instagram account, both acting with the Page's token (Page
 * tokens derived from a long-lived user token do not expire).
 *
 * <p>Instagram fetches images from a public URL and accepts JPEG only; the runtime supplies
 * both. Publishing an Instagram post is two steps: create a container, wait until Meta has
 * fetched the media, then publish it.
 */
@Component
public class MetaChannel implements PublishChannel {

    static final String FACEBOOK = "facebook";
    static final String INSTAGRAM = "instagram";
    /** Graph error codes for an invalid, expired or revoked token. */
    private static final Set<Integer> TOKEN_ERRORS = Set.of(102, 190);
    /** Graph error codes for rate limits and transient failures. */
    private static final Set<Integer> TRANSIENT_ERRORS = Set.of(1, 2, 4, 17, 32, 341, 613, 9007);
    static final int STATUS_POLLS = 20;
    private static final DateTimeFormatter GRAPH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final String appId;
    private final String appSecret;
    private final String version;
    private final String scopes;
    private final ChannelHttp http;
    private final Sleeper sleeper;
    private final ObjectMapper json = new ObjectMapper();

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public MetaChannel(@Value("${genesis.channels.meta.app-id:}") String appId,
                       @Value("${genesis.channels.meta.app-secret:}") String appSecret,
                       @Value("${genesis.channels.meta.graph-version:v26.0}") String version,
                       @Value("${genesis.channels.meta.scopes:pages_show_list,pages_read_engagement,pages_manage_posts,instagram_basic,instagram_content_publish,business_management}") String scopes) {
        this(appId, appSecret, version, scopes, new ChannelHttp(), Thread::sleep);
    }

    MetaChannel(String appId, String appSecret, String version, String scopes, ChannelHttp http, Sleeper sleeper) {
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.version = version;
        this.scopes = scopes;
        this.http = http;
        this.sleeper = sleeper;
    }

    @Override
    public String providerId() {
        return "meta";
    }

    @Override
    public String displayName() {
        return "Facebook and Instagram";
    }

    @Override
    public List<ChannelNetwork> networks() {
        return List.of(
            new ChannelNetwork(FACEBOOK, "Facebook Page", 63206, 10, false),
            new ChannelNetwork(INSTAGRAM, "Instagram", 2200, 10, true));
    }

    @Override
    public boolean configured() {
        return !appId.isEmpty() && !appSecret.isEmpty();
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", appId);
        q.put("redirect_uri", redirectUri);
        q.put("state", state);
        q.put("response_type", "code");
        q.put("scope", scopes);
        return "https://www.facebook.com/" + version + "/dialog/oauth?" + ChannelHttp.query(q);
    }

    @Override
    public List<ChannelGrant> completeConnect(String code, String redirectUri) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", appId);
        q.put("client_secret", appSecret);
        q.put("redirect_uri", redirectUri);
        q.put("code", code);
        String shortLived = read(http.get(graph("oauth/access_token") + "?" + ChannelHttp.query(q), Map.of())).path("access_token").asText("");

        Map<String, String> x = new LinkedHashMap<>();
        x.put("grant_type", "fb_exchange_token");
        x.put("client_id", appId);
        x.put("client_secret", appSecret);
        x.put("fb_exchange_token", shortLived);
        String userToken = read(http.get(graph("oauth/access_token") + "?" + ChannelHttp.query(x), Map.of())).path("access_token").asText("");

        List<ChannelGrant> grants = new ArrayList<>();
        String next = graph("me/accounts") + "?" + ChannelHttp.query(Map.of(
            "fields", "id,name,access_token,instagram_business_account{id,username}", "limit", "100"));
        for (int page = 0; next != null && page < 10; page++) {
            JsonNode body = read(http.get(next, bearer(userToken)));
            for (JsonNode p : body.path("data")) {
                String pageToken = p.path("access_token").asText("");
                if (pageToken.isEmpty()) continue;
                grants.add(new ChannelGrant(FACEBOOK, p.path("id").asText(), p.path("name").asText(), pageToken, null, null));
                JsonNode ig = p.path("instagram_business_account");
                if (ig.hasNonNull("id")) {
                    String handle = ig.path("username").asText("");
                    grants.add(new ChannelGrant(INSTAGRAM, ig.path("id").asText(),
                        handle.isEmpty() ? p.path("name").asText() : "@" + handle, pageToken, null, null));
                }
            }
            next = body.path("paging").hasNonNull("next") ? body.path("paging").path("next").asText() : null;
        }
        if (grants.isEmpty()) {
            throw ChannelException.permanent("No Facebook Pages were shared. Connect again and choose at least one Page.");
        }
        return grants;
    }

    @Override
    public PublishResult publish(ChannelAccountRef account, ChannelPost post) {
        return switch (account.network()) {
            case FACEBOOK -> publishFacebook(account, post);
            case INSTAGRAM -> publishInstagram(account, post);
            default -> throw ChannelException.permanent("Unknown Meta network: " + account.network());
        };
    }

    private PublishResult publishFacebook(ChannelAccountRef account, ChannelPost post) {
        Map<String, String> auth = bearer(account.accessToken());
        String pageId = account.externalId();
        List<Media> media = post.media();
        if (media.isEmpty()) {
            String id = read(http.postForm(graph(pageId + "/feed"), Map.of("message", post.text()), auth)).path("id").asText();
            return new PublishResult(id, "https://www.facebook.com/" + id);
        }
        if (media.size() == 1) {
            JsonNode photo = read(http.postForm(graph(pageId + "/photos"),
                Map.of("url", media.get(0).publicUrl(), "caption", post.text()), auth));
            String id = photo.path("post_id").asText(photo.path("id").asText());
            return new PublishResult(id, "https://www.facebook.com/" + id);
        }
        // Several photos: upload each unpublished, then one feed post that attaches them all.
        Map<String, String> feed = new LinkedHashMap<>();
        feed.put("message", post.text());
        for (int n = 0; n < media.size(); n++) {
            String photoId = read(http.postForm(graph(pageId + "/photos"),
                Map.of("url", media.get(n).publicUrl(), "published", "false"), auth)).path("id").asText();
            feed.put("attached_media[" + n + "]", "{\"media_fbid\":\"" + photoId + "\"}");
        }
        String id = read(http.postForm(graph(pageId + "/feed"), feed, auth)).path("id").asText();
        return new PublishResult(id, "https://www.facebook.com/" + id);
    }

    private PublishResult publishInstagram(ChannelAccountRef account, ChannelPost post) {
        Map<String, String> auth = bearer(account.accessToken());
        String igId = account.externalId();
        List<Media> media = post.media();
        if (media.isEmpty()) throw ChannelException.permanent("Instagram posts need at least one image.");

        String container;
        if (media.size() == 1) {
            container = read(http.postForm(graph(igId + "/media"),
                Map.of("image_url", media.get(0).publicUrl(), "caption", post.text()), auth)).path("id").asText();
        } else {
            List<String> children = new ArrayList<>();
            for (Media m : media.subList(0, Math.min(10, media.size()))) {
                String child = read(http.postForm(graph(igId + "/media"),
                    Map.of("image_url", m.publicUrl(), "is_carousel_item", "true"), auth)).path("id").asText();
                children.add(child);
            }
            for (String child : children) awaitContainer(child, auth);
            container = read(http.postForm(graph(igId + "/media"),
                Map.of("media_type", "CAROUSEL", "children", String.join(",", children), "caption", post.text()), auth)).path("id").asText();
        }
        awaitContainer(container, auth);
        String mediaId = read(http.postForm(graph(igId + "/media_publish"), Map.of("creation_id", container), auth)).path("id").asText();
        String url = null;
        try {
            url = read(http.get(graph(mediaId) + "?fields=permalink", auth)).path("permalink").asText(null);
        } catch (ChannelException ignored) {
            // Published; only the link lookup failed.
        }
        return new PublishResult(mediaId, url);
    }

    /** Reads a Page's feed ({@code pages_read_engagement}) or an Instagram account's media ({@code instagram_basic}). */
    @Override
    public List<RecentPost> recentPosts(ChannelAccountRef account, int limit) {
        int n = Math.max(1, Math.min(25, limit));
        Map<String, String> auth = bearer(account.accessToken());
        List<RecentPost> posts = new ArrayList<>();
        if (FACEBOOK.equals(account.network())) {
            String url = graph(account.externalId() + "/posts") + "?" + ChannelHttp.query(Map.of(
                "fields", "message,created_time,permalink_url,full_picture", "limit", String.valueOf(n)));
            for (JsonNode p : read(http.get(url, auth)).path("data")) {
                String text = p.path("message").asText("");
                if (text.isBlank() && !p.hasNonNull("full_picture")) continue;
                posts.add(new RecentPost(text, p.path("full_picture").asText(null), p.path("permalink_url").asText(null),
                    time(p.path("created_time").asText(""))));
            }
        } else if (INSTAGRAM.equals(account.network())) {
            String url = graph(account.externalId() + "/media") + "?" + ChannelHttp.query(Map.of(
                "fields", "caption,media_type,media_url,thumbnail_url,permalink,timestamp", "limit", String.valueOf(n)));
            for (JsonNode p : read(http.get(url, auth)).path("data")) {
                String image = "VIDEO".equals(p.path("media_type").asText()) ? p.path("thumbnail_url").asText(null)
                    : p.path("media_url").asText(null);
                posts.add(new RecentPost(p.path("caption").asText(""), image, p.path("permalink").asText(null),
                    time(p.path("timestamp").asText(""))));
            }
        }
        return posts;
    }

    /** Graph times look like {@code 2026-10-01T10:00:00+0000}. */
    static Instant time(String value) {
        try {
            return OffsetDateTime.parse(value, GRAPH_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Waits until Meta has fetched and processed a container's media. */
    private void awaitContainer(String containerId, Map<String, String> auth) {
        for (int n = 0; n < STATUS_POLLS; n++) {
            String status = read(http.get(graph(containerId) + "?fields=status_code", auth)).path("status_code").asText("");
            switch (status) {
                case "FINISHED", "PUBLISHED" -> { return; }
                case "ERROR" -> throw ChannelException.permanent("Instagram could not process the image. Check it is a JPEG under 8 MB.");
                case "EXPIRED" -> throw ChannelException.retryable("Instagram's upload expired before publishing.");
                default -> pause(3000);
            }
        }
        throw ChannelException.retryable("Instagram is still processing the image.");
    }

    private void pause(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ChannelException.retryable("Interrupted while waiting for Instagram.");
        }
    }

    private String graph(String path) {
        return "https://graph.facebook.com/" + version + "/" + path;
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    /** The response body, or the Graph error translated into what the client can do about it. */
    JsonNode read(ChannelHttp.Response res) {
        JsonNode body;
        try {
            body = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
        } catch (Exception e) {
            if (res.status() >= 500) throw ChannelException.retryable("Meta is not responding (" + res.status() + ").");
            throw ChannelException.permanent("Meta sent an unreadable reply (" + res.status() + ").");
        }
        if (res.ok() && !body.has("error")) return body;
        JsonNode err = body.path("error");
        int code = err.path("code").asInt(0);
        String message = err.path("error_user_msg").asText(err.path("message").asText("Meta refused the request (" + res.status() + ")."));
        if (TOKEN_ERRORS.contains(code) || res.status() == 401) {
            throw ChannelException.reauthorize("Meta no longer accepts this connection. Connect the account again. (" + message + ")");
        }
        if (TRANSIENT_ERRORS.contains(code) || res.status() >= 500 || res.status() == 429 || err.path("is_transient").asBoolean(false)) {
            throw ChannelException.retryable(message);
        }
        throw ChannelException.permanent(message);
    }
}
