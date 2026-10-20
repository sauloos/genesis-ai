package ai.genesisbrands.agent.occasions;

import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccasionsAgentTest {

    /** Today is 20 Oct 2026; six weeks ahead runs to 1 Dec 2026. */
    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");

    private static final String REPLY = """
        ```json
        {"occasions": [
          {"name": "Black Friday", "date": "2026-11-27", "why": "Gift-buying starts here.", "title": "Gifts that taste of the farm",
           "variants": [{"channel": "Instagram", "text": "Light roasts make the best gifts.", "hashtags": ["coffee gifts", "#blackfriday"]},
                        {"channel": "tiktok", "text": "not a chosen channel"}],
           "postImage": "A gift box of coffee beans on oak, #6b3e26 ribbon.",
           "headline": "Gifts that taste of the farm",
           "bannerImage": "Wide shot of wrapped coffee bags, space on the left.",
           "flyerImage": "Portrait of a coffee gift box.",
           "flyerText": "Find your gift in store."},
          {"name": "Christmas Day", "date": "2026-12-25", "title": "Outside the window", "variants": [{"channel": "instagram", "text": "x"}]},
          {"name": "Store anniversary", "date": "2026-11-14", "title": "Five years", "variants": [{"channel": "instagram", "text": "Five years of roasting."}],
           "postImage": "Our roastery at dawn."},
          {"name": "Black Friday", "date": "2026-11-27", "title": "Duplicate", "variants": [{"channel": "instagram", "text": "again"}]},
          {"name": "Bad date", "date": "next week", "variants": [{"channel": "instagram", "text": "x"}]},
        ]}
        ```""";

    record Painted(String brief, String size) {}

    private static OccasionsAgent agent(List<Painted> painted) {
        return new OccasionsAgent(new OccasionsAgent.Painter() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public byte[] render(String brief, String size) {
                painted.add(new Painted(brief, size));
                return new byte[] {1, 2, 3};
            }
        });
    }

    private static StandingRunContext ctx(Map<String, String> options, String memory, List<String> prompts, String reply) {
        Map<String, String> o = new HashMap<>(Map.of("region", "United States", "weeksAhead", "6", "perRun", "4",
            "kinds", "Public holidays,Retail moments,My own dates", "ownDates", "2026-11-14 Store anniversary\n03-08 Founders' day\nnot a date",
            "channels", "Instagram,LinkedIn", "assets", "Social post,Web banner,Print flyer", "leadDays", "5"));
        o.putAll(options);
        return new StandingRunContext("run-1", "sub-1", "client-1", "Northwind", o, "## Brand\nPalette: Roast Brown #6b3e26",
            List.of(new StandingRunContext.Decision("Halloween: Spooky roast", "REJECTED", "No Halloween, please", NOW)),
            null, NOW, null, ZoneOffset.UTC, prompt -> { prompts.add(prompt); return reply; }, memory);
    }

    @Test
    void draftsAPostAndABannerAndFlyerPerOccasionInTheWindow() {
        List<String> prompts = new ArrayList<>();
        List<Painted> painted = new ArrayList<>();
        StandingRunResult result = agent(painted).run(ctx(Map.of(), null, prompts, REPLY));

        String prompt = prompts.get(0);
        assertTrue(prompt.contains("Look ahead to Tuesday 1 Dec 2026 (6 weeks)"));
        assertTrue(prompt.contains("- 2026-11-14 Store anniversary"), "own dates in the window are listed");
        assertFalse(prompt.contains("Founders"), "a yearly date outside the window is not");
        assertTrue(prompt.contains("- 2026-11-26 Thanksgiving"));
        assertTrue(prompt.contains("- 2026-11-27 Black Friday"));
        assertTrue(prompt.contains("No Halloween, please"));

        List<StandingRunResult.Item> items = result.items();
        assertEquals(List.of("DRAFT_POST", "ASSET", "DRAFT_POST"), items.stream().map(StandingRunResult.Item::kind).toList());
        StandingRunResult.Item post = items.get(0);
        assertEquals("Black Friday: Gifts that taste of the farm", post.title());
        assertEquals("2026-11-22", post.payload().get("proposedFor"), "five days before");
        assertEquals(Map.of("name", "Black Friday", "date", "2026-11-27", "why", "Gift-buying starts here."), post.payload().get("occasion"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> variants = (List<Map<String, Object>>) post.payload().get("variants");
        assertEquals(1, variants.size());
        assertEquals(List.of("#coffeegifts", "#blackfriday"), variants.get(0).get("hashtags"));
        assertEquals(List.of("post.png"), post.attachments().stream().map(StandingRunResult.Attachment::name).toList());

        StandingRunResult.Item pack = items.get(1);
        assertEquals("Web banner, print flyer for Black Friday", pack.title());
        assertEquals(List.of("banner.png", "flyer.png"), pack.attachments().stream().map(StandingRunResult.Attachment::name).toList());
        assertEquals(List.of("1024x1024", "1536x1024", "1024x1536"), painted.subList(0, 3).stream().map(Painted::size).toList());
        assertTrue(painted.get(0).brief().contains("No text, letters"), "the post image has no words");
        assertTrue(painted.get(1).brief().contains("\"Gifts that taste of the farm\""), "the banner carries the headline");

        assertEquals("2026-11-14", ((Map<?, ?>) items.get(2).payload().get("occasion")).get("date"));
        assertEquals("2026-11-09", items.get(2).payload().get("proposedFor"));
        assertTrue(result.memory().contains("Black Friday") && result.memory().contains("Store anniversary"));
        assertFalse(result.memory().contains("Christmas"));
        assertEquals("2 occasions, 4 images", result.note());
    }

    @Test
    void neverProposesAnOccasionTwice() {
        List<String> prompts = new ArrayList<>();
        String memory = agent(new ArrayList<>()).run(ctx(Map.of(), null, new ArrayList<>(), REPLY)).memory();
        StandingRunResult again = agent(new ArrayList<>()).run(ctx(Map.of(), memory, prompts, REPLY));
        assertTrue(prompts.get(0).contains("Already proposed to the client; do not propose these again:\n- 2026-11-27 Black Friday"));
        assertTrue(again.items().isEmpty());
        assertEquals("No new occasions in the next 6 weeks", again.note());
    }

    @Test
    void forgetsOccasionsLongPast() {
        String old = "{\"proposed\":[{\"name\":\"Easter Sunday\",\"date\":\"2026-04-05\"},{\"name\":\"Labor Day\",\"date\":\"2026-09-07\"}]}";
        OccasionsAgent.Memory m = OccasionsAgent.Memory.parse(old, LocalDate.of(2026, 10, 20));
        assertFalse(m.has(OccasionsAgent.Memory.key(LocalDate.of(2026, 4, 5), "Easter Sunday")));
        assertTrue(m.has(OccasionsAgent.Memory.key(LocalDate.of(2026, 9, 7), "labor day")));
    }

    @Test
    void writesTextOnlyPostsWhenTheClientWantsNoImages() {
        List<Painted> painted = new ArrayList<>();
        StandingRunResult result = agent(painted).run(ctx(Map.of("imageStyle", "No images", "perRun", "1"), null, new ArrayList<>(), REPLY));
        assertTrue(painted.isEmpty());
        assertEquals(1, result.items().size());
        assertEquals("Text only", result.items().get(0).payload().get("format"));
    }

    @Test
    void aStoryOnlyClientGetsAPortraitPost() {
        List<Painted> painted = new ArrayList<>();
        StandingRunResult result = agent(painted).run(ctx(Map.of("assets", "Story", "perRun", "1"), null, new ArrayList<>(), REPLY));
        assertEquals(1, result.items().size());
        assertEquals("Story", result.items().get(0).payload().get("format"));
        assertEquals(List.of("1024x1536"), painted.stream().map(Painted::size).toList());
    }

    @Test
    void movingDatesAreRight() {
        assertEquals(LocalDate.of(2026, 4, 5), KnownDates.easter(2026));
        assertEquals(LocalDate.of(2027, 3, 28), KnownDates.easter(2027));
        assertEquals(LocalDate.of(2024, 3, 31), KnownDates.easter(2024));
        List<KnownDates.Known> uk = KnownDates.forYear(2027, "United Kingdom");
        assertTrue(uk.contains(new KnownDates.Known(LocalDate.of(2027, 3, 7), "Mother's Day (Mothering Sunday)")));
        List<KnownDates.Known> us = KnownDates.forYear(2026, "United States");
        assertTrue(us.contains(new KnownDates.Known(LocalDate.of(2026, 11, 26), "Thanksgiving")));
        assertTrue(us.contains(new KnownDates.Known(LocalDate.of(2026, 5, 25), "Memorial Day")));
        assertTrue(us.contains(new KnownDates.Known(LocalDate.of(2026, 5, 10), "Mother's Day")));
        assertTrue(KnownDates.forYear(2026, "Germany").contains(new KnownDates.Known(LocalDate.of(2026, 11, 29), "First Sunday of Advent")));
        assertTrue(KnownDates.forYear(2026, "Brazil").contains(new KnownDates.Known(LocalDate.of(2026, 2, 17), "Carnaval (Tuesday)")));
    }

    @Test
    void failsTheRunWhenTheReplyIsNotJson() {
        try {
            agent(new ArrayList<>()).run(ctx(Map.of(), null, new ArrayList<>(), "Sorry, I cannot help."));
        } catch (IllegalStateException e) {
            return;
        }
        throw new AssertionError("expected the run to fail");
    }
}
