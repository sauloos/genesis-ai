package ai.genesisbrands.channel.linkedin;

import ai.genesisbrands.platform.channel.ChannelException;
import ai.genesisbrands.platform.channel.ChannelHttp;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelAccountRef;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelGrant;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelPost;
import ai.genesisbrands.platform.channel.PublishChannel.Media;
import ai.genesisbrands.platform.channel.PublishChannel.PublishResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkedInChannelTest {

    static final class Script {
        final Deque<ChannelHttp.Response> replies = new ArrayDeque<>();
        final List<ChannelHttp.Request> sent = new ArrayList<>();

        Script reply(int status, String body, Map<String, List<String>> headers) {
            replies.add(new ChannelHttp.Response(status, body, headers));
            return this;
        }

        Script reply(int status, String body) {
            return reply(status, body, Map.of());
        }

        ChannelHttp http() {
            return new ChannelHttp(req -> {
                sent.add(req);
                if (replies.isEmpty()) throw new AssertionError("Unexpected request: " + req.method() + " " + req.url());
                return replies.poll();
            });
        }
    }

    private static final ChannelAccountRef ME = new ChannelAccountRef("linkedin", "urn:li:person:abc", "Ada", "tok");

    @Test
    void connectGrantsTheMemberAndAdministeredPages() {
        Script s = new Script()
            .reply(200, "{\"access_token\":\"tok\",\"expires_in\":5184000,\"refresh_token\":\"ref\"}")
            .reply(200, "{\"sub\":\"abc\",\"name\":\"Ada Lovelace\"}")
            .reply(200, "{\"elements\":[{\"organization\":\"urn:li:organization:42\"}]}")
            .reply(200, "{\"localizedName\":\"Northwind\"}");
        LinkedInChannel li = new LinkedInChannel("id", "secret", "202610", "openid profile w_member_social w_organization_social", s.http());
        List<ChannelGrant> grants = li.completeConnect("code", "https://x.test/cb");

        assertEquals(2, grants.size());
        assertEquals("urn:li:person:abc", grants.get(0).externalId());
        assertEquals("ref", grants.get(0).refreshToken());
        assertNotNull(grants.get(0).expiresAt());
        assertEquals("Northwind", grants.get(1).displayName());
        assertEquals("202610", s.sent.get(2).headers().get("LinkedIn-Version"));
    }

    @Test
    void memberOnlyScopesSkipPages() {
        Script s = new Script().reply(200, "{\"access_token\":\"tok\"}").reply(200, "{\"sub\":\"abc\",\"name\":\"Ada\"}");
        List<ChannelGrant> grants = new LinkedInChannel("id", "secret", "202610", "openid profile w_member_social", s.http())
            .completeConnect("code", "https://x.test/cb");
        assertEquals(1, grants.size());
    }

    @Test
    void publishUploadsImagesThenPostsWithMultiImage() throws Exception {
        Script s = new Script()
            .reply(200, "{\"value\":{\"uploadUrl\":\"https://upload.test/1\",\"image\":\"urn:li:image:1\"}}")
            .reply(201, "")
            .reply(200, "{\"value\":{\"uploadUrl\":\"https://upload.test/2\",\"image\":\"urn:li:image:2\"}}")
            .reply(201, "")
            .reply(201, "", Map.of("x-restli-id", List.of("urn:li:share:7")));
        LinkedInChannel li = new LinkedInChannel("id", "secret", "202610", "w_member_social", s.http());
        PublishResult r = li.publish(ME, new ChannelPost("Hello (world) #coffee",
            List.of(new Media("image/jpeg", new byte[] {1}, null), new Media("image/jpeg", new byte[] {2}, null))));

        assertEquals("PUT", s.sent.get(1).method());
        assertEquals("https://upload.test/1", s.sent.get(1).url());
        JsonNode post = new ObjectMapper().readTree(new String(s.sent.get(4).body(), StandardCharsets.UTF_8));
        assertEquals("urn:li:person:abc", post.path("author").asText());
        assertEquals("Hello \\(world\\) #coffee", post.path("commentary").asText());
        assertEquals(2, post.path("content").path("multiImage").path("images").size());
        assertEquals("urn:li:share:7", r.externalPostId());
        assertEquals("https://www.linkedin.com/feed/update/urn:li:share:7/", r.url());
    }

    @Test
    void escapeKeepsHashtagsAndEscapesReservedCharacters() {
        assertEquals("#coffee in C\\# \\@ home \\*now\\*", LinkedInChannel.escape("#coffee in C# @ home *now*"));
        assertEquals("a\\#b", LinkedInChannel.escape("a#b"));
    }

    @Test
    void errorsAreClassified() {
        LinkedInChannel li = new LinkedInChannel("id", "secret", "202610", "w_member_social", new Script().http());
        assertTrue(assertThrows(ChannelException.class,
            () -> li.read(new ChannelHttp.Response(401, "{\"message\":\"expired\"}", Map.of()))).reauthorize());
        assertTrue(assertThrows(ChannelException.class,
            () -> li.read(new ChannelHttp.Response(429, "", Map.of()))).retryable());
        ChannelException bad = assertThrows(ChannelException.class,
            () -> li.read(new ChannelHttp.Response(422, "{\"message\":\"Too long\"}", Map.of())));
        assertEquals("Too long", bad.getMessage());
    }
}
