package ai.genesisbrands.agent.brandguardian;

import ai.genesisbrands.platform.standing.SocialPostReader;
import ai.genesisbrands.platform.standing.SocialPostReader.SocialPost;
import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandGuardianAgentTest {

    private static final String REPLY = """
        ```json
        {"score": 140, "headline": "Voice is right, the site drifts off-palette",
         "summary": "Mostly on brand. Swap the button orange first.",
         "dimensions": [{"name": "Colours", "score": 58, "note": "Orange buttons."},
                        {"name": "Imagery", "score": 10, "note": "not asked for"},
                        {"name": "Voice and tone", "score": 88, "note": "Warm."}],
         "onBrand": ["Instagram captions follow the tone guide."],
         "findings": [{"severity": "low", "area": "Typography", "where": "https://northwind.example/", "issue": "Arial body text.", "evidence": "Arial", "fix": "Use Inter."},
                      {"severity": "HIGH", "area": "Colours", "where": "https://northwind.example/", "issue": "Off-palette orange.", "evidence": "#ff7a00", "fix": "Use #6b3e26."},
                      {"severity": "odd", "issue": "Generic hero.", "fix": "Use the tagline."},
                      {"severity": "low", "issue": ""}],
         "quickFixes": ["Replace #ff7a00."],}
        ```""";

    private static StandingRunContext ctx(Map<String, String> options, List<String> prompts, String reply) {
        return new StandingRunContext("run-1", "sub-1", "client-1", "Northwind", options,
            "## Brand\nPalette: Roast Brown #6b3e26", List.of(new StandingRunContext.Decision("Brand check: 70/100", "REJECTED",
                "The orange is our seasonal colour, ignore it", Instant.parse("2026-10-01T00:00:00Z"))),
            Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-19T09:00:00Z"), null, ZoneOffset.UTC,
            prompt -> { prompts.add(prompt); return reply; });
    }

    private static WebsiteReader site() {
        return new WebsiteReader(host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")}, uri ->
            uri.getPath().equals("/") || uri.getPath().isEmpty()
                ? new WebsiteReader.Fetched(200, null, "text/html", "<title>Northwind</title><h1>The best coffee in town</h1><style>a{color:#ff7a00}</style>"
                    .getBytes(StandardCharsets.UTF_8))
                : new WebsiteReader.Fetched(404, null, "text/html", new byte[0]));
    }

    private static SocialPostReader feed(List<SocialPost> posts, List<String> unreadable) {
        return (client, perAccount) -> {
            assertEquals("client-1", client);
            return new SocialPostReader.Feed(posts, unreadable);
        };
    }

    @Test
    void scoresTheSiteAndPostsIntoOneRankedReport() {
        List<String> prompts = new ArrayList<>();
        SocialPostReader reader = feed(List.of(
            new SocialPost("instagram", "@northwind", "Light roasts taste of the farm.", "https://cdn.test/1.jpg", "https://ig.test/1",
                Instant.parse("2026-10-10T08:00:00Z")),
            new SocialPost("facebook", "Northwind", "  ", null, null, null)), List.of("Facebook Page Old: expired"));
        BrandGuardianAgent agent = new BrandGuardianAgent(() -> reader, site());
        StandingRunResult result = agent.run(ctx(Map.of("pages", "northwind.example\nhttps://northwind.example/missing",
            "focus", "Voice and tone,Colours", "notes", "our old tagline"), prompts, REPLY));

        String prompt = prompts.get(0);
        assertTrue(prompt.contains("Focus areas: Voice and tone, Colours"));
        assertTrue(prompt.contains("watch for: our old tagline"));
        assertTrue(prompt.contains("ignore it"));
        assertTrue(prompt.contains("#ff7a00 (1)"));
        assertTrue(prompt.contains("Instagram · @northwind · 10 Oct 2026 (new since the last check) · has an image"));
        assertFalse(prompt.contains("Northwind · "), "blank posts are not sent");

        StandingRunResult.Item item = result.items().get(0);
        assertEquals(StandingRunResult.Item.REPORT, item.kind());
        assertEquals("Brand check: 100/100 — Voice is right, the site drifts off-palette", item.title());
        @SuppressWarnings("unchecked") Map<String, Object> card = (Map<String, Object>) item.payload().get("scorecard");
        @SuppressWarnings("unchecked") List<Map<String, Object>> dims = (List<Map<String, Object>>) card.get("dimensions");
        assertEquals(List.of("Colours", "Voice and tone"), dims.stream().map(d -> d.get("name")).toList());
        @SuppressWarnings("unchecked") List<Map<String, Object>> findings = (List<Map<String, Object>>) card.get("findings");
        assertEquals(List.of("high", "medium", "low"), findings.stream().map(f -> f.get("severity")).toList());
        @SuppressWarnings("unchecked") List<Map<String, Object>> sources = (List<Map<String, Object>>) card.get("sources");
        assertEquals(4, sources.size());
        assertEquals(false, sources.get(1).get("ok"));
        assertTrue(String.valueOf(sources.get(1).get("result")).contains("404"));
        assertTrue(item.body().contains("Fix: Use #6b3e26."));
        assertTrue(result.note().contains("1 pages, 1 posts"));
    }

    @Test
    void reportsNothingToCheckWithoutCallingTheModel() {
        List<String> prompts = new ArrayList<>();
        BrandGuardianAgent agent = new BrandGuardianAgent(() -> null, site());
        StandingRunResult result = agent.run(ctx(Map.of("pages", "", "socials", "true"), prompts, REPLY));
        assertTrue(prompts.isEmpty());
        assertEquals("Brand check: nothing to check yet", result.items().get(0).title());
    }

    @Test
    void waitsForABrandBookWithoutReadingAnything() {
        List<String> prompts = new ArrayList<>();
        BrandGuardianAgent agent = new BrandGuardianAgent(() -> { throw new AssertionError("must not read posts"); },
            new WebsiteReader(host -> { throw new AssertionError("must not read the site"); }, uri -> null));
        StandingRunContext base = ctx(Map.of("pages", "northwind.example"), prompts, REPLY);
        StandingRunContext noBook = new StandingRunContext(base.runId(), base.subscriptionId(), base.clientUserId(),
            base.clientName(), base.options(), " ", base.recentDecisions(), base.previousRunAt(), base.now(),
            base.nextRunAt(), base.zone(), base.model());
        StandingRunResult result = agent.run(noBook);
        assertTrue(prompts.isEmpty());
        assertEquals("Brand check: waiting for your brand book", result.items().get(0).title());
    }

    @Test
    void leavesSocialAccountsAloneWhenTheClientTurnedThatOff() {
        List<String> prompts = new ArrayList<>();
        BrandGuardianAgent agent = new BrandGuardianAgent(() -> { throw new AssertionError("must not read posts"); }, site());
        agent.run(ctx(Map.of("pages", "northwind.example", "socials", "false"), prompts, REPLY));
        assertEquals(1, prompts.size());
    }

    @Test
    void rejectsAReplyWithoutAScore() {
        assertNull(BrandGuardianAgent.Report.parse("{\"headline\":\"x\"}", BrandGuardianAgent.FOCUS));
        assertNull(BrandGuardianAgent.Report.parse("no json", BrandGuardianAgent.FOCUS));
    }

    @Test
    void capsPagesAndPostsPerAccount() {
        StandingRunContext c = ctx(Map.of("pages", "a.example b.example, c.example\nd.example e.example f.example a.example",
            "postsPerAccount", "99"), new ArrayList<>(), REPLY);
        assertEquals(List.of("a.example", "b.example", "c.example", "d.example", "e.example"), BrandGuardianAgent.pages(c));
        assertEquals(20, BrandGuardianAgent.postsPerAccount(c));
    }
}
