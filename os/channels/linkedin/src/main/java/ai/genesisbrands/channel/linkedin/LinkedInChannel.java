package ai.genesisbrands.channel.linkedin;

import ai.genesisbrands.platform.channel.ChannelException;
import ai.genesisbrands.platform.channel.ChannelHttp;
import ai.genesisbrands.platform.channel.PublishChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LinkedIn through the versioned REST API ({@code /rest/posts}, {@code /rest/images}). The
 * member's own profile needs only "Share on LinkedIn" ({@code w_member_social}); organisation
 * pages need the Community Management API ({@code w_organization_social}), which LinkedIn
 * approves per app. Organisations are offered only when the configured scopes ask for them.
 *
 * <p>Access tokens last about 60 days and LinkedIn grants refresh tokens only to approved
 * partners, so an account expires and the client connects it again.
 */
@Component
public class LinkedInChannel implements PublishChannel {

    static final String NETWORK = "linkedin";
    static final String API = "https://api.linkedin.com";
    /** Characters LinkedIn's "little text" commentary format reserves; they are escaped with a backslash. */
    private static final String RESERVED = "\\|{}@[]()<>*_~";

    private final String clientId;
    private final String clientSecret;
    private final String version;
    private final String scopes;
    private final ChannelHttp http;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public LinkedInChannel(@Value("${genesis.channels.linkedin.client-id:}") String clientId,
                           @Value("${genesis.channels.linkedin.client-secret:}") String clientSecret,
                           @Value("${genesis.channels.linkedin.api-version:202610}") String version,
                           @Value("${genesis.channels.linkedin.scopes:openid profile w_member_social}") String scopes) {
        this(clientId, clientSecret, version, scopes, new ChannelHttp());
    }

    LinkedInChannel(String clientId, String clientSecret, String version, String scopes, ChannelHttp http) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.version = version;
        this.scopes = scopes;
        this.http = http;
    }

    @Override
    public String providerId() {
        return "linkedin";
    }

    @Override
    public String displayName() {
        return "LinkedIn";
    }

    @Override
    public List<ChannelNetwork> networks() {
        return List.of(new ChannelNetwork(NETWORK, "LinkedIn", 3000, 20, false));
    }

    @Override
    public boolean configured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    boolean wantsOrganizations() {
        return Arrays.asList(scopes.split("[\\s,]+")).contains("w_organization_social");
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", clientId);
        q.put("redirect_uri", redirectUri);
        q.put("state", state);
        q.put("scope", String.join(" ", scopes.trim().split("[\\s,]+")));
        return "https://www.linkedin.com/oauth/v2/authorization?" + ChannelHttp.query(q);
    }

    @Override
    public List<ChannelGrant> completeConnect(String code, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        JsonNode token = read(http.postForm("https://www.linkedin.com/oauth/v2/accessToken", form, Map.of()));
        String access = token.path("access_token").asText("");
        if (access.isEmpty()) throw ChannelException.permanent("LinkedIn did not return an access token.");
        Instant expires = token.hasNonNull("expires_in") ? Instant.now().plusSeconds(token.get("expires_in").asLong()) : null;
        String refresh = token.path("refresh_token").asText(null);

        JsonNode me = read(http.get(API + "/v2/userinfo", Map.of("Authorization", "Bearer " + access)));
        List<ChannelGrant> grants = new ArrayList<>();
        grants.add(new ChannelGrant(NETWORK, "urn:li:person:" + me.path("sub").asText(),
            me.path("name").asText("LinkedIn profile"), access, refresh, expires));

        if (wantsOrganizations()) {
            JsonNode acls = read(http.get(API + "/rest/organizationAcls?q=roleAssignee&role=ADMINISTRATOR&state=APPROVED",
                headers(access)));
            for (JsonNode acl : acls.path("elements")) {
                String urn = acl.path("organization").asText("");
                if (!urn.startsWith("urn:li:organization:")) continue;
                String name = urn;
                try {
                    name = read(http.get(API + "/rest/organizations/" + urn.substring("urn:li:organization:".length()),
                        headers(access))).path("localizedName").asText(urn);
                } catch (ChannelException ignored) {
                    // The page is still usable under its URN.
                }
                grants.add(new ChannelGrant(NETWORK, urn, name, access, refresh, expires));
            }
        }
        return grants;
    }

    @Override
    public PublishResult publish(ChannelAccountRef account, ChannelPost post) {
        String token = account.accessToken();
        String author = account.externalId();
        List<String> images = new ArrayList<>();
        for (Media m : post.media().subList(0, Math.min(20, post.media().size()))) {
            images.add(upload(author, m, token));
        }

        ObjectNode body = json.createObjectNode();
        body.put("author", author);
        body.put("commentary", escape(post.text()));
        body.put("visibility", "PUBLIC");
        ObjectNode distribution = body.putObject("distribution");
        distribution.put("feedDistribution", "MAIN_FEED");
        distribution.putArray("targetEntities");
        distribution.putArray("thirdPartyDistributionChannels");
        if (images.size() == 1) {
            body.putObject("content").putObject("media").put("id", images.get(0));
        } else if (images.size() > 1) {
            ArrayNode list = body.putObject("content").putObject("multiImage").putArray("images");
            images.forEach(id -> list.addObject().put("id", id));
        }
        body.put("lifecycleState", "PUBLISHED");
        body.put("isReshareDisabledByAuthor", false);

        ChannelHttp.Response res = http.postJson(API + "/rest/posts", body.toString(), headers(token));
        read(res);
        String urn = res.header("x-restli-id").or(() -> res.header("x-linkedin-id")).orElse(null);
        return new PublishResult(urn, urn == null ? null : "https://www.linkedin.com/feed/update/" + urn + "/");
    }

    /**
     * An organisation post's share statistics ({@code r_organization_social}); otherwise the
     * post's likes and comments ({@code r_member_social} for a member's own posts). LinkedIn
     * grants both read scopes only to approved apps, so a refusal just leaves figures unknown.
     */
    @Override
    public Optional<PostMetrics> metrics(ChannelAccountRef account, String externalPostId) {
        if (externalPostId == null || externalPostId.isBlank()) return Optional.empty();
        Map<String, String> headers = headers(account.accessToken());
        String author = account.externalId();
        if (author.startsWith("urn:li:organization:")) {
            String param = externalPostId.startsWith("urn:li:ugcPost:") ? "ugcPosts" : "shares";
            JsonNode stats = readOrNull(API + "/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity="
                + encode(author) + "&" + param + "=List(" + encode(externalPostId) + ")", headers);
            JsonNode total = stats == null ? null : stats.path("elements").path(0).path("totalShareStatistics");
            if (total != null && total.isObject()) {
                PostMetrics m = new PostMetrics(number(total, "impressionCount"), number(total, "uniqueImpressionsCount"),
                    number(total, "likeCount"), number(total, "commentCount"), number(total, "shareCount"), null,
                    number(total, "clickCount"));
                if (m.any()) return Optional.of(m);
            }
        }
        JsonNode social = readOrNull(API + "/rest/socialActions/" + encode(externalPostId), headers);
        if (social == null) return Optional.empty();
        Long likes = number(social.path("likesSummary"), "totalLikes");
        Long comments = number(social.path("commentsSummary"), "aggregatedTotalComments");
        if (comments == null) comments = number(social.path("commentsSummary"), "totalFirstLevelComments");
        PostMetrics m = new PostMetrics(null, null, likes, comments, null, null, null);
        return m.any() ? Optional.of(m) : Optional.empty();
    }

    private static Long number(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isNumber() ? node.get(field).asLong() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The body, or null when LinkedIn refused for a reason other than the token (usually a scope the app lacks). */
    private JsonNode readOrNull(String url, Map<String, String> headers) {
        try {
            return read(http.get(url, headers));
        } catch (ChannelException e) {
            if (e.reauthorize()) throw e;
            return null;
        }
    }

    /** Registers an image with LinkedIn, then uploads its bytes; returns the image URN. */
    private String upload(String owner, Media media, String token) {
        ObjectNode init = json.createObjectNode();
        init.putObject("initializeUploadRequest").put("owner", owner);
        JsonNode value = read(http.postJson(API + "/rest/images?action=initializeUpload", init.toString(), headers(token))).path("value");
        String uploadUrl = value.path("uploadUrl").asText("");
        String image = value.path("image").asText("");
        if (uploadUrl.isEmpty() || image.isEmpty()) throw ChannelException.retryable("LinkedIn did not accept the image upload.");
        read(http.put(uploadUrl, media.bytes(), media.contentType(), Map.of("Authorization", "Bearer " + token)));
        return image;
    }

    private Map<String, String> headers(String token) {
        return Map.of(
            "Authorization", "Bearer " + token,
            "LinkedIn-Version", version,
            "X-Restli-Protocol-Version", "2.0.0");
    }

    /**
     * Escapes "little text" reserved characters so the copy is posted as written. A {@code #}
     * that starts a word stays a hashtag; any other {@code #} is escaped.
     */
    static String escape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '#') {
                boolean wordStart = i == 0 || Character.isWhitespace(text.charAt(i - 1));
                boolean tag = i + 1 < text.length() && Character.isLetterOrDigit(text.charAt(i + 1));
                if (!(wordStart && tag)) out.append('\\');
            } else if (RESERVED.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    JsonNode read(ChannelHttp.Response res) {
        JsonNode body;
        try {
            body = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
        } catch (Exception e) {
            body = json.createObjectNode();
        }
        if (res.ok()) return body;
        String message = body.path("message").asText(body.path("error_description").asText("LinkedIn refused the request (" + res.status() + ")."));
        if (res.status() == 401) {
            throw ChannelException.reauthorize("LinkedIn no longer accepts this connection. Connect the account again. (" + message + ")");
        }
        if (res.status() == 429 || res.status() >= 500) throw ChannelException.retryable(message);
        throw ChannelException.permanent(message);
    }
}
