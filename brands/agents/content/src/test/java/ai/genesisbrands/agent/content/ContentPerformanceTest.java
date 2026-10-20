package ai.genesisbrands.agent.content;

import ai.genesisbrands.platform.standing.PostPerformance;
import ai.genesisbrands.platform.standing.PostPerformance.Metrics;
import ai.genesisbrands.platform.standing.PostPerformance.PublishedPost;
import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContentPerformanceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");

    private static PublishedPost post(String title, String network, String pillar, String format, Long reactions, Long comments) {
        return new PublishedPost("i-" + title, title, Map.of("pillar", pillar, "format", format), network, "Acme",
            NOW.minusSeconds(86400), new Metrics(null, 900L, reactions, comments, null, null, null, NOW));
    }

    private static final List<String> PILLARS = List.of("Education", "Behind the scenes", "Product");
    private static final List<String> FORMATS = List.of("Single image", "Carousel");

    @Test
    void scoresEachPostAgainstItsOwnNetworkAndSharesTheBatchByTopic() {
        // Instagram averages 50, LinkedIn 10: the LinkedIn post at 20 is as strong as Instagram's 100.
        List<PublishedPost> posts = List.of(
            post("Roast day", "instagram", "Behind the scenes", "Carousel", 90L, 10L),
            post("Grind size", "instagram", "Education", "Single image", 0L, 0L),
            post("Team", "linkedin", "behind the scenes", "Carousel", 18L, 2L),
            post("Why light", "linkedin", "Education", "Single image", 0L, 0L));
        ContentPerformance p = ContentPerformance.of(posts, PILLARS, FORMATS, 4);
        assertNotNull(p);
        assertEquals(4, p.measured());
        // Behind the scenes 2.0x, Product untested (1.0x), Education 0x floored to 0.25: 2.46 / 1.23 / 0.31 of 4.
        assertEquals(Map.of("Behind the scenes", 3, "Product", 1, "Education", 0), p.mix());
        assertEquals(List.of("Behind the scenes", "Product", "Education"), new ArrayList<>(p.mix().keySet()));
        assertTrue(p.section().contains("- Behind the scenes: 2.0× over 2 posts"), p.section());
        assertTrue(p.section().contains("- Product: nothing measured yet"));
        assertTrue(p.section().contains("- Carousel: 2.0× over 2 posts"));
        assertTrue(p.section().contains("\"Roast day\" (Instagram, 2.0×: 90 reactions, 10 comments, reach 900)"), p.section());
        assertTrue(p.section().contains("Aim for this topic mix: 3 Behind the scenes, 1 Product."), p.section());
    }

    @Test
    void needsAtLeastTwoMeasuredPosts() {
        PublishedPost unmeasured = new PublishedPost("x", "X", Map.of(), "instagram", "Acme", NOW, null);
        assertNull(ContentPerformance.of(List.of(post("A", "instagram", "Education", "Carousel", 3L, 1L), unmeasured),
            PILLARS, FORMATS, 3));
        assertNull(ContentPerformance.of(null, PILLARS, FORMATS, 3));
    }

    @Test
    void noEngagementAnywhereCountsEveryTopicAsAverage() {
        ContentPerformance p = ContentPerformance.of(List.of(
            post("A", "instagram", "Education", "Carousel", 0L, 0L),
            post("B", "instagram", "Product", "Carousel", 0L, 0L)), PILLARS, FORMATS, 3);
        assertEquals(3, p.mix().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(p.mix().values().stream().allMatch(n -> n == 1));
    }

    @Test
    void theRunAsksForTheAgentsOwnPostsAndWeightsThePrompt() {
        AtomicReference<String> asked = new AtomicReference<>();
        AtomicReference<String> prompt = new AtomicReference<>();
        PostPerformance source = (client, agent, since) -> {
            asked.set(client + "/" + agent + "/" + since);
            return List.of(
                post("Roast day", "instagram", "Behind the scenes", "Carousel", 90L, 10L),
                post("Grind size", "instagram", "Education", "Single image", 2L, 0L));
        };
        ContentAgent agent = new ContentAgent(null, () -> source);
        StandingRunContext ctx = new StandingRunContext("run", "sub", "client-1", "Acme", Map.of("imageStyle", "No images",
            "posts", "2", "pillars", "Education,Behind the scenes"), "", List.of(), null, NOW, null, ZoneOffset.UTC, p -> {
                prompt.set(p);
                return "{\"posts\":[{\"title\":\"A\",\"format\":\"Text only\",\"variants\":[{\"channel\":\"instagram\",\"text\":\"x\"}]}]}";
            });
        StandingRunResult result = agent.run(ctx);
        assertEquals("client-1/content/2026-08-21T09:00:00Z", asked.get());
        assertTrue(prompt.get().contains("What performed:"), prompt.get());
        assertTrue(prompt.get().contains("Aim for this topic mix: 2 Behind the scenes."), prompt.get());
        assertEquals("1 post, 0 images, weighted by 2 published posts", result.note());
    }

    @Test
    void withoutPublishingOrWhenItFailsTheRunStillDrafts() {
        StandingRunContext ctx = new StandingRunContext("run", "sub", "c", "Acme", Map.of("imageStyle", "No images"), "",
            List.of(), null, NOW, null, ZoneOffset.UTC,
            p -> "{\"posts\":[{\"title\":\"A\",\"format\":\"Text only\",\"variants\":[{\"channel\":\"instagram\",\"text\":\"x\"}]}]}");
        assertEquals("1 post, 0 images", new ContentAgent(null, () -> null).run(ctx).note());
        PostPerformance broken = (client, agent, since) -> { throw new IllegalStateException("db down"); };
        assertEquals("1 post, 0 images", new ContentAgent(null, () -> broken).run(ctx).note());
    }
}
