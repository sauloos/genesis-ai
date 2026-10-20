package ai.genesisbrands.channel.meta;

import ai.genesisbrands.platform.channel.ChannelException;
import ai.genesisbrands.platform.channel.ChannelHttp;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelAccountRef;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelGrant;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelPost;
import ai.genesisbrands.platform.channel.PublishChannel.Media;
import ai.genesisbrands.platform.channel.PublishChannel.PublishResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaChannelTest {

    /** Replies in order and keeps every request, so a test can check what was sent. */
    static final class Script {
        final Deque<ChannelHttp.Response> replies = new ArrayDeque<>();
        final List<ChannelHttp.Request> sent = new ArrayList<>();

        Script reply(int status, String body) {
            replies.add(new ChannelHttp.Response(status, body, Map.of()));
            return this;
        }

        ChannelHttp http() {
            return new ChannelHttp(req -> {
                sent.add(req);
                if (replies.isEmpty()) throw new AssertionError("Unexpected request: " + req.method() + " " + req.url());
                return replies.poll();
            });
        }

        String body(int n) {
            return new String(sent.get(n).body(), StandardCharsets.UTF_8);
        }
    }

    private static MetaChannel channel(Script s) {
        return new MetaChannel("app", "secret", "v26.0", "pages_show_list", s.http(), ms -> {});
    }

    private static final ChannelAccountRef PAGE = new ChannelAccountRef("facebook", "123", "Northwind", "page-token");
    private static final ChannelAccountRef IG = new ChannelAccountRef("instagram", "999", "@northwind", "page-token");

    private static Media image(String url) {
        return new Media("image/jpeg", new byte[] {1}, url);
    }

    @Test
    void connectGrantsEachPageAndItsInstagramAccount() {
        Script s = new Script()
            .reply(200, "{\"access_token\":\"short\"}")
            .reply(200, "{\"access_token\":\"long\"}")
            .reply(200, """
                {"data":[
                  {"id":"1","name":"Northwind","access_token":"p1","instagram_business_account":{"id":"ig1","username":"northwind"}},
                  {"id":"2","name":"No token"},
                  {"id":"3","name":"Southwind","access_token":"p3"}]}""");
        List<ChannelGrant> grants = channel(s).completeConnect("code", "https://x.test/cb");

        assertEquals(3, grants.size());
        assertEquals("facebook", grants.get(0).network());
        assertEquals("instagram", grants.get(1).network());
        assertEquals("@northwind", grants.get(1).displayName());
        assertEquals("p1", grants.get(1).accessToken());
        assertEquals("Southwind", grants.get(2).displayName());
        assertTrue(s.sent.get(1).url().contains("grant_type=fb_exchange_token"));
        assertEquals("Bearer long", s.sent.get(2).headers().get("Authorization"));
    }

    @Test
    void connectWithNoPagesIsRefused() {
        Script s = new Script().reply(200, "{\"access_token\":\"a\"}").reply(200, "{\"access_token\":\"b\"}").reply(200, "{\"data\":[]}");
        assertThrows(ChannelException.class, () -> channel(s).completeConnect("code", "https://x.test/cb"));
    }

    @Test
    void facebookTextPostGoesToTheFeed() {
        Script s = new Script().reply(200, "{\"id\":\"123_456\"}");
        PublishResult r = channel(s).publish(PAGE, new ChannelPost("Hello", null));
        assertTrue(s.sent.get(0).url().endsWith("/v26.0/123/feed"));
        assertTrue(s.body(0).contains("message=Hello"));
        assertEquals("https://www.facebook.com/123_456", r.url());
    }

    @Test
    void facebookSeveralPhotosAreAttachedToOnePost() {
        Script s = new Script().reply(200, "{\"id\":\"p1\"}").reply(200, "{\"id\":\"p2\"}").reply(200, "{\"id\":\"123_9\"}");
        channel(s).publish(PAGE, new ChannelPost("Two", List.of(image("https://x.test/a.jpg"), image("https://x.test/b.jpg"))));
        assertTrue(s.body(0).contains("published=false"));
        assertTrue(s.sent.get(2).url().endsWith("/123/feed"));
        assertTrue(s.body(2).contains("attached_media%5B1%5D"));
        assertTrue(s.body(2).contains("p2"));
    }

    @Test
    void instagramCarouselWaitsForEveryContainerThenPublishes() {
        Script s = new Script()
            .reply(200, "{\"id\":\"c1\"}").reply(200, "{\"id\":\"c2\"}")
            .reply(200, "{\"status_code\":\"IN_PROGRESS\"}").reply(200, "{\"status_code\":\"FINISHED\"}")
            .reply(200, "{\"status_code\":\"FINISHED\"}")
            .reply(200, "{\"id\":\"carousel\"}").reply(200, "{\"status_code\":\"FINISHED\"}")
            .reply(200, "{\"id\":\"media1\"}")
            .reply(200, "{\"permalink\":\"https://instagram.com/p/x\"}");
        PublishResult r = channel(s).publish(IG, new ChannelPost("Caption", List.of(image("https://x.test/a.jpg"), image("https://x.test/b.jpg"))));
        assertEquals("media1", r.externalPostId());
        assertEquals("https://instagram.com/p/x", r.url());
        assertTrue(s.body(5).contains("media_type=CAROUSEL"));
        assertTrue(s.body(5).contains("children=c1%2Cc2"));
        assertTrue(s.sent.get(7).url().endsWith("/999/media_publish"));
    }

    @Test
    void instagramNeedsAnImage() {
        ChannelException e = assertThrows(ChannelException.class, () -> channel(new Script()).publish(IG, new ChannelPost("x", List.of())));
        assertFalse(e.retryable());
    }

    @Test
    void graphErrorsAreClassified() {
        MetaChannel meta = channel(new Script());
        ChannelException token = assertThrows(ChannelException.class,
            () -> meta.read(new ChannelHttp.Response(400, "{\"error\":{\"code\":190,\"message\":\"expired\"}}", Map.of())));
        assertTrue(token.reauthorize());
        ChannelException limit = assertThrows(ChannelException.class,
            () -> meta.read(new ChannelHttp.Response(400, "{\"error\":{\"code\":4,\"message\":\"slow down\"}}", Map.of())));
        assertTrue(limit.retryable());
        ChannelException bad = assertThrows(ChannelException.class,
            () -> meta.read(new ChannelHttp.Response(400, "{\"error\":{\"code\":100,\"error_user_msg\":\"Caption too long\"}}", Map.of())));
        assertFalse(bad.retryable());
        assertEquals("Caption too long", bad.getMessage());
    }

    @Test
    void readsRecentFacebookPostsSkippingEmptyOnes() {
        Script s = new Script().reply(200, """
            {"data":[
              {"message":"Light roast week","created_time":"2026-10-01T10:00:00+0000","permalink_url":"https://fb.test/p/1","full_picture":"https://cdn.test/1.jpg"},
              {"created_time":"2026-09-30T10:00:00+0000"},
              {"message":"Open late on Friday","created_time":"bad"}]}""");
        var posts = channel(s).recentPosts(PAGE, 5);
        assertEquals(2, posts.size());
        assertEquals("Light roast week", posts.get(0).text());
        assertEquals("https://cdn.test/1.jpg", posts.get(0).imageUrl());
        assertEquals(java.time.Instant.parse("2026-10-01T10:00:00Z"), posts.get(0).postedAt());
        assertEquals(null, posts.get(1).postedAt());
        assertTrue(s.sent.get(0).url().contains("/v26.0/123/posts?"));
        assertTrue(s.sent.get(0).url().contains("limit=5"));
    }

    @Test
    void readsRecentInstagramMediaUsingVideoThumbnails() {
        Script s = new Script().reply(200, """
            {"data":[
              {"caption":"Reel","media_type":"VIDEO","media_url":"https://cdn.test/v.mp4","thumbnail_url":"https://cdn.test/t.jpg","permalink":"https://ig.test/r","timestamp":"2026-10-02T08:30:00+0000"},
              {"caption":"Beans","media_type":"IMAGE","media_url":"https://cdn.test/b.jpg","permalink":"https://ig.test/b","timestamp":"2026-10-01T08:30:00+0000"}]}""");
        var posts = channel(s).recentPosts(IG, 50);
        assertEquals(2, posts.size());
        assertEquals("https://cdn.test/t.jpg", posts.get(0).imageUrl());
        assertEquals("https://cdn.test/b.jpg", posts.get(1).imageUrl());
        assertTrue(s.sent.get(0).url().contains("/v26.0/999/media?"));
        assertTrue(s.sent.get(0).url().contains("limit=25"));
    }

    @Test
    void readingPostsWithARevokedTokenAsksToReconnect() {
        Script s = new Script().reply(400, "{\"error\":{\"code\":190,\"message\":\"expired\"}}");
        ChannelException e = assertThrows(ChannelException.class, () -> channel(s).recentPosts(PAGE, 5));
        assertTrue(e.getMessage().contains("Connect the account again"));
    }
}
