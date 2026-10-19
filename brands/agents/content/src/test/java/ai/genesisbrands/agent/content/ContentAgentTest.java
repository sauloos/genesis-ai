package ai.genesisbrands.agent.content;

import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContentAgentTest {

    private static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");

    private static StandingRunContext ctx(Map<String, String> options, Instant next, ZoneId zone) {
        return new StandingRunContext("run", "sub", "client", "Acme Coffee", options, "Brand: Acme Coffee",
            List.of(new StandingRunContext.Decision("Our roast story", "REJECTED", "Too salesy", NOW)),
            null, NOW, next, zone, prompt -> "");
    }

    @Test
    void parsesFencedRepliesAndKeepsOnlyChosenChannels() {
        String reply = """
            ```json
            {"posts": [
              {"title": "Why we roast light", "summary": "Explains the house style.", "pillar": "Education",
               "format": "Carousel", "day": 3,
               "variants": [
                 {"channel": "instagram", "text": "Light roasts taste of the farm.", "hashtags": ["coffee", "#roasting"]},
                 {"channel": "facebook", "text": "Not chosen", "hashtags": []},
                 {"channel": "LinkedIn", "text": "A note on sourcing.", "hashtags": []}],
               "images": ["beans", "cupping", "farm", "too many"]},
              {"title": "Empty", "variants": [{"channel": "instagram", "text": " "}]},
            ]}
            ```""";
        List<ContentAgent.Post> posts = ContentAgent.Post.parseAll(reply, List.of("Instagram", "LinkedIn"));
        assertEquals(1, posts.size());
        ContentAgent.Post p = posts.get(0);
        assertEquals(List.of("Instagram", "LinkedIn"), p.variants().stream().map(ContentAgent.Variant::channel).toList());
        assertEquals(List.of("#coffee", "#roasting"), p.variants().get(0).hashtags());
        assertEquals(3, p.images().size());
        assertEquals(3, p.day());
    }

    @Test
    void textOnlyPostsHaveNoImagesAndUnknownFormatsFallBack() {
        String reply = "{\"posts\":[{\"title\":\"A\",\"format\":\"Text only\",\"variants\":[{\"channel\":\"linkedin\",\"text\":\"x\"}],\"images\":[\"a\"]},"
            + "{\"title\":\"B\",\"format\":\"Reel\",\"variants\":[{\"channel\":\"linkedin\",\"text\":\"y\"}],\"images\":[\"a\",\"b\"]}]}";
        List<ContentAgent.Post> posts = ContentAgent.Post.parseAll(reply, List.of("LinkedIn"));
        assertTrue(posts.get(0).images().isEmpty());
        assertEquals("Single image", posts.get(1).format());
        assertEquals(1, posts.get(1).images().size());
    }

    @Test
    void garbageParsesToNothing() {
        assertTrue(ContentAgent.Post.parseAll("Sorry, I can't.", List.of("Instagram")).isEmpty());
        assertTrue(ContentAgent.Post.parseAll(null, List.of("Instagram")).isEmpty());
    }

    @Test
    void periodRunsToTheNextRunInTheClientsZone() {
        ContentAgent.Period week = ContentAgent.Period.of(ctx(Map.of(), NOW.plusSeconds(7 * 86400), ZoneId.of("Pacific/Auckland")));
        assertEquals(LocalDate.of(2026, 10, 19), week.start());
        assertEquals(7, week.days());
        assertEquals(LocalDate.of(2026, 10, 21), week.dayOf(3));
        assertEquals(LocalDate.of(2026, 10, 25), week.dayOf(40));
        assertEquals(LocalDate.of(2026, 10, 19), week.dayOf(0));

        ContentAgent.Period auckland = ContentAgent.Period.of(ctx(Map.of(), null,
            ZoneId.of("Pacific/Auckland")));
        assertEquals(7, auckland.days());
        assertEquals(LocalDate.of(2026, 10, 19), auckland.start());
        ContentAgent.Period late = ContentAgent.Period.of(new StandingRunContext("r", "s", "c", "n", Map.of(), "", List.of(),
            null, Instant.parse("2026-10-19T13:00:00Z"), null, ZoneId.of("Pacific/Auckland"), p -> ""));
        assertEquals(LocalDate.of(2026, 10, 20), late.start());
    }

    @Test
    void promptCarriesOptionsAndFeedback() {
        StandingRunContext c = ctx(Map.of("channels", "LinkedIn,Bogus", "posts", "12", "avoid", "prices", "language", "Portuguese"),
            NOW.plusSeconds(14 * 86400), ZoneOffset.UTC);
        String prompt = ContentAgent.prompt(c, ContentAgent.Period.of(c));
        assertTrue(prompt.contains("Write 7 posts."));
        assertTrue(prompt.contains("Channels: LinkedIn\n"));
        assertTrue(prompt.contains("covers 14 days"));
        assertTrue(prompt.contains("Avoid: prices"));
        assertTrue(prompt.contains("Write in: Portuguese"));
        assertTrue(prompt.contains("\"Our roast story\": rejected — \"Too salesy\""));
        assertTrue(prompt.contains("<client_context>\nBrand: Acme Coffee"));
    }

    @Test
    void itemFollowsTheDraftPostShape() {
        ContentAgent.Post post = new ContentAgent.Post("T", "", "Education", "Story", 2,
            List.of(new ContentAgent.Variant("Instagram", "Hello", List.of("#a"))), List.of("b"));
        StandingRunResult.Item item = post.toItem(LocalDate.of(2026, 10, 20), List.of());
        assertEquals(StandingRunResult.Item.DRAFT_POST, item.kind());
        assertEquals("2026-10-20", item.payload().get("proposedFor"));
        assertEquals("Story", item.payload().get("format"));
        @SuppressWarnings("unchecked")
        Map<String, Object> v = ((List<Map<String, Object>>) item.payload().get("variants")).get(0);
        assertEquals("instagram", v.get("channel"));
        assertEquals("Instagram", v.get("label"));
        assertTrue(item.body().contains("## Instagram\n\nHello\n\n#a"));
        assertTrue(item.summary().contains("Tuesday 20 Oct 2026"));
    }

    @Test
    void imageBriefsForbidLetteringExceptTypographic() {
        assertTrue(ContentAgent.imageBrief("beans", ctx(Map.of(), null, ZoneOffset.UTC)).contains("No text"));
        assertFalse(ContentAgent.imageBrief("SALE", ctx(Map.of("imageStyle", "Typographic"), null, ZoneOffset.UTC)).contains("No text"));
    }
}
