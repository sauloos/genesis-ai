package ai.genesisbrands.agent.brandguardian;

import ai.genesisbrands.platform.AgentCustomOption;
import ai.genesisbrands.platform.AgentCustomOption.OptionType;
import ai.genesisbrands.platform.CoreAgent;
import ai.genesisbrands.platform.standing.Cadence;
import ai.genesisbrands.platform.standing.SocialPostReader;
import ai.genesisbrands.platform.standing.SocialPostReader.SocialPost;
import ai.genesisbrands.platform.standing.StandingAgent;
import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import ai.genesisbrands.platform.web.WebsiteReader;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Genesis Brands standing agent: each run reads the client's website pages and recent social
 * posts and scores them against the client's own brand book (tone, messaging, palette,
 * typography), with a ranked list of fixes. It only reports; the client decides what to change.
 * Tenant-specific because the standard it judges against is the brand book the brand
 * engagement produced.
 */
@Component
public class BrandGuardianAgent implements CoreAgent, StandingAgent {

    static final String ID = "brand-guardian";
    static final List<String> FOCUS = List.of("Voice and tone", "Messaging", "Colours", "Typography", "Consistency");
    static final int MAX_PAGES = 5;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA, JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final Supplier<SocialPostReader> posts;
    private final WebsiteReader website;

    @Autowired
    public BrandGuardianAgent(ObjectProvider<SocialPostReader> posts) {
        this(posts::getIfAvailable, new WebsiteReader());
    }

    BrandGuardianAgent(Supplier<SocialPostReader> posts, WebsiteReader website) {
        this.posts = posts;
        this.website = website;
    }

    @Override
    public String agentId() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Brand guardian";
    }

    @Override
    public String description() {
        return "Checks your website and recent posts against your brand book and tells you what drifted and how to fix it.";
    }

    @Override
    public String icon() {
        return "🛡️";
    }

    @Override
    public String testEndpoint() {
        return "/api/client/standing/offers";
    }

    @Override
    public boolean supportsPlayground() {
        return false;
    }

    @Override
    public boolean requiresEngagementContext() {
        return false;
    }

    @Override
    public Set<Cadence> allowedCadences() {
        return EnumSet.of(Cadence.WEEKLY, Cadence.FORTNIGHTLY, Cadence.MONTHLY);
    }

    @Override
    public Cadence defaultCadence() {
        return Cadence.MONTHLY;
    }

    @Override
    public List<AgentCustomOption> subscriptionOptions() {
        return List.of(
            new AgentCustomOption("pages", "Website pages to check", OptionType.TEXT, List.of(), "",
                "One address per line, up to " + MAX_PAGES + ". Your home page is a good start."),
            new AgentCustomOption("socials", "Check my connected social accounts", OptionType.BOOLEAN, List.of(), "true",
                "Reads your latest posts on the accounts you connected for publishing."),
            new AgentCustomOption("postsPerAccount", "Posts to check per account", OptionType.NUMBER, List.of("3", "20"), "10", null),
            new AgentCustomOption("focus", "What to check", OptionType.MULTI_SELECT, FOCUS, String.join(",", FOCUS), null),
            new AgentCustomOption("notes", "Anything to watch for", OptionType.TEXT, List.of(), "",
                "For example a retired tagline, a competitor's wording, or a new product name."),
            new AgentCustomOption("language", "Report language", OptionType.SELECT,
                List.of("English", "Portuguese", "Spanish", "French", "German"), "English", null));
    }

    @Override
    public StandingRunResult run(StandingRunContext ctx) {
        if (ctx.clientContext() == null || ctx.clientContext().isBlank()) return noBrandBook();
        List<Source> sources = new ArrayList<>();
        List<WebsiteReader.Page> pages = new ArrayList<>();
        for (String address : pages(ctx)) {
            try {
                WebsiteReader.Page page = website.read(address);
                pages.add(page);
                sources.add(new Source("Website", page.url(), true, page.title().isEmpty() ? "read" : page.title()));
            } catch (WebsiteReader.Unreadable e) {
                sources.add(new Source("Website", address, false, "could not be read: " + e.getMessage()));
            }
        }
        List<SocialPost> social = new ArrayList<>();
        if (!"false".equalsIgnoreCase(opt(ctx, "socials"))) {
            SocialPostReader reader = posts.get();
            SocialPostReader.Feed feed = reader == null ? SocialPostReader.Feed.empty()
                : reader.recentPosts(ctx.clientUserId(), postsPerAccount(ctx));
            social.addAll(feed.posts().stream().filter(p -> p.text() != null && !p.text().isBlank()).toList());
            Map<String, Integer> perAccount = new LinkedHashMap<>();
            for (SocialPost p : social) perAccount.merge(networkLabel(p.network()) + " · " + p.account(), 1, Integer::sum);
            perAccount.forEach((account, n) -> sources.add(new Source("Social", account, true, n + (n == 1 ? " post" : " posts"))));
            for (String line : feed.unreadable()) sources.add(new Source("Social", line, false, "could not be read"));
        }

        if (pages.isEmpty() && social.isEmpty()) return nothingToCheck(sources);

        Report report = Report.parse(ctx.model().complete(prompt(ctx, pages, social)), focus(ctx));
        if (report == null) throw new IllegalStateException("The model returned no usable report");
        String note = "score " + report.score() + ", " + report.findings().size()
            + (report.findings().size() == 1 ? " finding" : " findings") + ", " + pages.size() + " pages, " + social.size() + " posts";
        return new StandingRunResult(List.of(report.toItem(sources)), note);
    }

    /** A report that explains how to give the agent something to check, without calling the model. */
    static StandingRunResult nothingToCheck(List<Source> sources) {
        StringBuilder body = new StringBuilder("There was nothing to check this time.\n\n");
        for (Source s : sources) body.append("- ").append(s.label()).append(" ").append(s.detail()).append(": ").append(s.result()).append("\n");
        body.append("\nAdd your website's address in this agent's settings, or connect your social accounts, and the next check will review them.");
        Map<String, Object> scorecard = new LinkedHashMap<>();
        scorecard.put("sources", sources.stream().map(Source::toMap).toList());
        return new StandingRunResult(List.of(new StandingRunResult.Item(StandingRunResult.Item.REPORT,
            "Brand check: nothing to check yet", "Add your website or connect a social account to start.",
            body.toString().strip(), Map.of("scorecard", scorecard))), "nothing to check");
    }

    /** Without a brand book there is no standard to judge against, so nothing is read and the model is not called. */
    static StandingRunResult noBrandBook() {
        return new StandingRunResult(List.of(StandingRunResult.Item.report(
            "Brand check: waiting for your brand book",
            "The check starts once your brand book is ready.",
            "The Brand guardian compares your website and posts with your own brand book, and there is no brand book "
                + "on record yet. Once your brand engagement is complete, the next check will review them.")),
            "no brand book");
    }

    static String prompt(StandingRunContext ctx, List<WebsiteReader.Page> pages, List<SocialPost> social) {
        ZoneId zone = ctx.zone() == null ? ZoneOffset.UTC : ctx.zone();
        StringBuilder p = new StringBuilder();
        p.append("Client: ").append(ctx.clientName()).append("\n");
        p.append("Focus areas: ").append(String.join(", ", focus(ctx))).append("\n");
        p.append("Write in: ").append(or(opt(ctx, "language"), "English")).append("\n");
        if (!opt(ctx, "notes").isBlank()) p.append("The client asks you to watch for: ").append(opt(ctx, "notes")).append("\n");
        if (!ctx.recentDecisions().isEmpty()) {
            p.append("\nThe client's responses to earlier reports (follow their notes):\n");
            for (StandingRunContext.Decision d : ctx.recentDecisions()) {
                p.append("- \"").append(d.title()).append("\": ").append(d.status().toLowerCase(Locale.ROOT));
                if (d.feedback() != null && !d.feedback().isBlank()) p.append(" — \"").append(d.feedback().trim()).append("\"");
                p.append("\n");
            }
        }
        p.append("\n<brand_book>\n")
            .append(ctx.clientContext() == null || ctx.clientContext().isBlank() ? "(nothing on record yet)" : ctx.clientContext())
            .append("\n</brand_book>\n");
        for (WebsiteReader.Page page : pages) {
            p.append("\n<website_page url=\"").append(page.url()).append("\">\n");
            p.append("Title: ").append(page.title()).append("\n");
            if (!page.description().isEmpty()) p.append("Description: ").append(page.description()).append("\n");
            if (!page.headings().isEmpty()) p.append("Headings: ").append(String.join(" | ", page.headings())).append("\n");
            if (!page.colours().isEmpty()) {
                List<String> cs = new ArrayList<>();
                page.colours().forEach((c, n) -> cs.add(c + " (" + n + ")"));
                p.append("Most used CSS colours: ").append(String.join(", ", cs)).append("\n");
            }
            if (!page.fonts().isEmpty()) p.append("Font families: ").append(String.join(", ", page.fonts())).append("\n");
            p.append("Visible text:\n").append(page.text()).append("\n</website_page>\n");
        }
        if (!social.isEmpty()) {
            p.append("\n<social_posts>\n");
            for (SocialPost s : social) {
                p.append("- ").append(networkLabel(s.network())).append(" · ").append(s.account());
                if (s.postedAt() != null) {
                    p.append(" · ").append(DAY.format(s.postedAt().atZone(zone)));
                    if (ctx.previousRunAt() != null && s.postedAt().isAfter(ctx.previousRunAt())) p.append(" (new since the last check)");
                }
                if (s.imageUrl() != null) p.append(" · has an image");
                p.append("\n  ").append(WebsiteReader.clip(s.text(), 1200).replace("\n", "\n  ")).append("\n");
            }
            p.append("</social_posts>\n");
        }
        return p.toString();
    }

    static List<String> pages(StandingRunContext ctx) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : opt(ctx, "pages").split("[\\s,]+")) {
            if (!s.isBlank()) out.add(s.strip());
            if (out.size() >= MAX_PAGES) break;
        }
        return List.copyOf(out);
    }

    static List<String> focus(StandingRunContext ctx) {
        List<String> chosen = Arrays.stream(opt(ctx, "focus").split(",")).map(String::strip).filter(FOCUS::contains).toList();
        return chosen.isEmpty() ? FOCUS : chosen;
    }

    static int postsPerAccount(StandingRunContext ctx) {
        try {
            return Math.max(3, Math.min(20, Integer.parseInt(opt(ctx, "postsPerAccount"))));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    static String networkLabel(String network) {
        if (network == null || network.isEmpty()) return "Social";
        return switch (network) {
            case "linkedin" -> "LinkedIn";
            default -> Character.toUpperCase(network.charAt(0)) + network.substring(1);
        };
    }

    private static String opt(StandingRunContext ctx, String key) {
        String value = ctx.option(key);
        return value == null ? "" : value.trim();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** One thing the run looked at, and whether it could. */
    record Source(String label, String detail, boolean ok, String result) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("detail", detail);
            m.put("ok", ok);
            m.put("result", result);
            return m;
        }
    }

    record Dimension(String name, int score, String note) {}

    record Finding(String severity, String area, String where, String issue, String evidence, String fix) {}

    /** The model's report, with scores clamped and areas limited to the ones asked for. */
    record Report(int score, String headline, String summary, List<Dimension> dimensions, List<String> onBrand,
                  List<Finding> findings, List<String> quickFixes) {

        static final List<String> SEVERITIES = List.of("high", "medium", "low");

        static Report parse(String reply, List<String> focus) {
            if (reply == null) return null;
            int from = reply.indexOf('{'), to = reply.lastIndexOf('}');
            if (from < 0 || to <= from) return null;
            JsonNode root;
            try {
                root = JSON.readTree(reply.substring(from, to + 1));
            } catch (Exception e) {
                return null;
            }
            if (!root.path("score").canConvertToInt()) return null;
            List<Dimension> dims = new ArrayList<>();
            for (JsonNode d : root.path("dimensions")) {
                String name = d.path("name").asText("").strip();
                if (!focus.contains(name) || dims.stream().anyMatch(x -> x.name().equals(name))) continue;
                dims.add(new Dimension(name, clamp(d.path("score").asInt(0)), d.path("note").asText("").strip()));
            }
            List<Finding> findings = new ArrayList<>();
            for (JsonNode f : root.path("findings")) {
                String issue = f.path("issue").asText("").strip();
                if (issue.isEmpty()) continue;
                String severity = f.path("severity").asText("").strip().toLowerCase(Locale.ROOT);
                findings.add(new Finding(SEVERITIES.contains(severity) ? severity : "medium", f.path("area").asText("").strip(),
                    f.path("where").asText("").strip(), issue, f.path("evidence").asText("").strip(), f.path("fix").asText("").strip()));
                if (findings.size() >= 8) break;
            }
            findings.sort((a, b) -> SEVERITIES.indexOf(a.severity()) - SEVERITIES.indexOf(b.severity()));
            String headline = WebsiteReader.clip(root.path("headline").asText("").strip(), 70);
            return new Report(clamp(root.path("score").asInt()), headline, root.path("summary").asText("").strip(), dims,
                strings(root.path("onBrand")), findings, strings(root.path("quickFixes")));
        }

        StandingRunResult.Item toItem(List<Source> sources) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("score", score);
            card.put("dimensions", dimensions.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", d.name());
                m.put("score", d.score());
                m.put("note", d.note());
                return m;
            }).toList());
            card.put("highlights", onBrand);
            card.put("findings", findings.stream().map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("severity", f.severity());
                m.put("area", f.area());
                m.put("where", f.where());
                m.put("issue", f.issue());
                m.put("evidence", f.evidence());
                m.put("fix", f.fix());
                return m;
            }).toList());
            card.put("quickFixes", quickFixes);
            card.put("sources", sources.stream().map(Source::toMap).toList());

            StringBuilder body = new StringBuilder();
            body.append("Overall: ").append(score).append("/100").append(summary.isEmpty() ? "" : ". " + summary).append("\n");
            if (!dimensions.isEmpty()) {
                body.append("\nScores\n");
                for (Dimension d : dimensions) {
                    body.append("- ").append(d.name()).append(": ").append(d.score()).append("/100");
                    if (!d.note().isEmpty()) body.append(" — ").append(d.note());
                    body.append("\n");
                }
            }
            if (!findings.isEmpty()) {
                body.append("\nWhat to fix\n");
                for (Finding f : findings) {
                    body.append("- [").append(f.severity()).append("] ").append(f.issue());
                    if (!f.where().isEmpty()) body.append(" (").append(f.where()).append(")");
                    if (!f.evidence().isEmpty()) body.append("\n  Seen: ").append(f.evidence());
                    if (!f.fix().isEmpty()) body.append("\n  Fix: ").append(f.fix());
                    body.append("\n");
                }
            }
            if (!onBrand.isEmpty()) {
                body.append("\nWhat's working\n");
                onBrand.forEach(s -> body.append("- ").append(s).append("\n"));
            }
            if (!quickFixes.isEmpty()) {
                body.append("\nQuick fixes\n");
                quickFixes.forEach(s -> body.append("- ").append(s).append("\n"));
            }
            body.append("\nChecked\n");
            for (Source s : sources) body.append("- ").append(s.label()).append(" ").append(s.detail()).append(": ").append(s.result()).append("\n");

            String title = "Brand check: " + score + "/100" + (headline.isEmpty() ? "" : " — " + headline);
            String lead = summary.isEmpty() ? findings.size() + (findings.size() == 1 ? " thing" : " things") + " to fix" : summary;
            return new StandingRunResult.Item(StandingRunResult.Item.REPORT, title, WebsiteReader.clip(lead, 200),
                body.toString().strip(), Map.of("scorecard", card));
        }

        private static int clamp(int n) {
            return Math.max(0, Math.min(100, n));
        }

        private static List<String> strings(JsonNode array) {
            List<String> out = new ArrayList<>();
            for (JsonNode n : array) {
                String s = n.asText("").strip();
                if (!s.isEmpty()) out.add(s);
                if (out.size() >= 6) break;
            }
            return out;
        }
    }
}
