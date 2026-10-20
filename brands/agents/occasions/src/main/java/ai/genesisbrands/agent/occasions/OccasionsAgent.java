package ai.genesisbrands.agent.occasions;

import ai.genesisbrands.agent.content.ContentImages;
import ai.genesisbrands.platform.AgentCustomOption;
import ai.genesisbrands.platform.AgentCustomOption.OptionType;
import ai.genesisbrands.platform.CoreAgent;
import ai.genesisbrands.platform.standing.Cadence;
import ai.genesisbrands.platform.standing.StandingAgent;
import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Genesis Brands standing agent: looks a few weeks ahead for the occasions that matter to the
 * client (holidays, retail moments, awareness days, their own dates) and drafts the post, and
 * optionally a web banner, story and print flyer, early enough to use them. It only drafts; the
 * client approves. Remembers what it already proposed, so each occasion arrives once.
 */
@Component
public class OccasionsAgent implements CoreAgent, StandingAgent {

    static final String ID = "occasions";
    static final List<String> CHANNELS = List.of("Instagram", "Facebook", "LinkedIn");
    static final List<String> KINDS = List.of("Public holidays", "Retail moments", "Awareness days", "My own dates");
    static final List<String> ASSETS = List.of("Social post", "Story", "Web banner", "Print flyer");
    static final List<String> REGIONS = List.of("United States", "United Kingdom", "Ireland", "Canada", "Australia",
        "Brazil", "Portugal", "Spain", "France", "Germany", "Global");
    static final List<String> IMAGE_STYLES = List.of("Brand photography", "Illustration", "Typographic", "Palette only", "No images");
    /** Forget proposed occasions this long after their date, so next year's arrives again. */
    static final int FORGET_AFTER_DAYS = 60;
    private static final Pattern OWN_DATE = Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2})\\s*[:\\-–—,]?\\s*(.+?)\\s*$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM yyyy", Locale.ENGLISH);
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA, JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /** The image model, behind a seam so tests need no OpenAI client. */
    interface Painter {
        boolean available();

        byte[] render(String brief, String size);
    }

    private final Painter painter;

    @Autowired
    public OccasionsAgent(ContentImages images) {
        this(new Painter() {
            @Override
            public boolean available() {
                return images.available();
            }

            @Override
            public byte[] render(String brief, String size) {
                return images.render(brief, size);
            }
        });
    }

    OccasionsAgent(Painter painter) {
        this.painter = painter;
    }

    @Override
    public String agentId() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Occasions";
    }

    @Override
    public String description() {
        return "Spots the holidays, retail moments and your own dates coming up, and drafts the posts, banners and flyers in time.";
    }

    @Override
    public String icon() {
        return "📅";
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
        return Cadence.FORTNIGHTLY;
    }

    @Override
    public List<AgentCustomOption> subscriptionOptions() {
        return List.of(
            new AgentCustomOption("region", "Where you trade", OptionType.SELECT, REGIONS, "United States",
                "Decides which holidays and dates apply."),
            new AgentCustomOption("weeksAhead", "Weeks ahead", OptionType.NUMBER, List.of("2", "12"), "6",
                "How far ahead to look for occasions."),
            new AgentCustomOption("kinds", "Occasions", OptionType.MULTI_SELECT, KINDS, "Public holidays,Retail moments,My own dates", null),
            new AgentCustomOption("ownDates", "Your dates", OptionType.TEXT, List.of(), "",
                "One per line, e.g. \"2026-11-14 Store anniversary\" or \"03-08 Founders' day\" for every year."),
            new AgentCustomOption("perRun", "Occasions per run", OptionType.NUMBER, List.of("1", "4"), "2", null),
            new AgentCustomOption("leadDays", "Days before", OptionType.NUMBER, List.of("0", "21"), "5",
                "How many days before the occasion to post."),
            new AgentCustomOption("assets", "What to make", OptionType.MULTI_SELECT, ASSETS, "Social post,Web banner", null),
            new AgentCustomOption("channels", "Channels", OptionType.MULTI_SELECT, CHANNELS, "Instagram,LinkedIn",
                "Each post is written once per channel."),
            new AgentCustomOption("imageStyle", "Images", OptionType.SELECT, IMAGE_STYLES, "Brand photography", null),
            new AgentCustomOption("language", "Language", OptionType.SELECT,
                List.of("English", "Portuguese", "Spanish", "French", "German"), "English", null),
            new AgentCustomOption("avoid", "Avoid", OptionType.TEXT, List.of(), "",
                "Occasions, topics or claims to stay away from."));
    }

    @Override
    public StandingRunResult run(StandingRunContext ctx) {
        Window window = Window.of(ctx);
        Memory memory = Memory.parse(ctx.memory(), window.start());
        List<KnownDates.Known> known = kinds(ctx).stream().anyMatch(k -> !k.equals("My own dates"))
            ? KnownDates.between(window.start(), window.end(), opt(ctx, "region")) : List.of();
        List<KnownDates.Known> own = kinds(ctx).contains("My own dates") ? ownDates(ctx, window) : List.of();

        List<Occasion> occasions = Occasion.parseAll(ctx.model().complete(prompt(ctx, window, known, own, memory)), channels(ctx));
        Set<String> assets = assets(ctx);
        boolean draw = !"No images".equals(opt(ctx, "imageStyle")) && painter.available();
        List<StandingRunResult.Item> items = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        int imageCount = 0, used = 0;
        for (Occasion o : occasions) {
            if (used >= perRun(ctx)) break;
            if (!window.contains(o.date())) continue;
            String key = Memory.key(o.date(), o.name());
            if (memory.has(key) || !taken.add(key)) continue;
            used++;
            memory.add(key, o.name(), o.date());

            boolean post = assets.contains("Social post") || assets.contains("Story");
            boolean storyAsPost = !assets.contains("Social post") && assets.contains("Story");
            if (post && !o.variants().isEmpty()) {
                List<StandingRunResult.Attachment> files = new ArrayList<>();
                if (draw && !o.postImage().isEmpty()) {
                    byte[] png = painter.render(imageBrief(o.postImage(), ctx, storyAsPost ? "Portrait" : "Square", null),
                        storyAsPost ? "1024x1536" : "1024x1024");
                    if (png != null) files.add(new StandingRunResult.Attachment(storyAsPost ? "story.png" : "post.png", "image/png", png));
                }
                imageCount += files.size();
                String format = !draw ? "Text only" : storyAsPost ? "Story" : "Single image";
                items.add(o.toPost(window.postDay(o.date(), leadDays(ctx)), format, files));
            }

            if (draw) {
                List<StandingRunResult.Attachment> files = new ArrayList<>();
                List<Map<String, Object>> made = new ArrayList<>();
                if (assets.contains("Social post") && assets.contains("Story") && !o.postImage().isEmpty()) {
                    paint(files, made, "story.png", "Story", "1024x1536",
                        imageBrief(o.postImage(), ctx, "Portrait 9:16 story", null));
                }
                if (assets.contains("Web banner") && !o.bannerImage().isEmpty()) {
                    paint(files, made, "banner.png", "Web banner", "1536x1024",
                        imageBrief(o.bannerImage(), ctx, "Wide web banner", o.headline()));
                }
                if (assets.contains("Print flyer") && !o.flyerImage().isEmpty()) {
                    paint(files, made, "flyer.png", "Print flyer", "1024x1536",
                        imageBrief(o.flyerImage(), ctx, "Portrait print flyer", o.headline()));
                }
                imageCount += files.size();
                if (!files.isEmpty()) items.add(o.toAssets(files, made));
            }
        }
        String note = used == 0 ? "No new occasions in the next " + window.weeks() + " weeks"
            : used + (used == 1 ? " occasion, " : " occasions, ") + imageCount + (imageCount == 1 ? " image" : " images");
        return new StandingRunResult(items, note, memory.toJson());
    }

    private void paint(List<StandingRunResult.Attachment> files, List<Map<String, Object>> made, String name, String label,
                       String size, String brief) {
        byte[] png = painter.render(brief, size);
        if (png == null) return;
        files.add(new StandingRunResult.Attachment(name, "image/png", png));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("label", label);
        m.put("size", size.replace('x', '×'));
        made.add(m);
    }

    static String prompt(StandingRunContext ctx, Window window, List<KnownDates.Known> known, List<KnownDates.Known> own, Memory memory) {
        StringBuilder p = new StringBuilder();
        p.append("Client: ").append(ctx.clientName()).append("\n");
        p.append("Today is ").append(DAY.format(window.start())).append(". Look ahead to ").append(DAY.format(window.end()))
            .append(" (").append(window.weeks()).append(" weeks).\n");
        p.append("Where the client trades: ").append(or(opt(ctx, "region"), "United States")).append("\n");
        p.append("Kinds of occasion to consider: ").append(String.join(", ", kinds(ctx))).append("\n");
        p.append("Pick at most ").append(perRun(ctx)).append(perRun(ctx) == 1 ? " occasion" : " occasions")
            .append(", the ones that fit this brand best. Fewer is fine; none is fine when nothing fits.\n");
        p.append("Make: ").append(String.join(", ", assets(ctx))).append("\n");
        p.append("Channels: ").append(String.join(", ", channels(ctx))).append("\n");
        p.append("Image style: ").append(or(opt(ctx, "imageStyle"), "Brand photography")).append("\n");
        p.append("Write in: ").append(or(opt(ctx, "language"), "English")).append("\n");
        if (!opt(ctx, "avoid").isBlank()) p.append("Avoid: ").append(opt(ctx, "avoid")).append("\n");
        if (!own.isEmpty()) {
            p.append("\nThe client's own dates in this window (always consider these first):\n");
            for (KnownDates.Known k : own) p.append("- ").append(k.date()).append(" ").append(k.name()).append("\n");
        }
        if (!known.isEmpty()) {
            p.append("\nDates that move each year, already worked out for this window (use these exact dates):\n");
            for (KnownDates.Known k : known) p.append("- ").append(k.date()).append(" ").append(k.name()).append("\n");
        }
        List<String> proposed = memory.inWindow(window);
        if (!proposed.isEmpty()) {
            p.append("\nAlready proposed to the client; do not propose these again:\n");
            for (String s : proposed) p.append("- ").append(s).append("\n");
        }
        if (!ctx.recentDecisions().isEmpty()) {
            p.append("\nWhat the client did with earlier drafts (follow their feedback):\n");
            for (StandingRunContext.Decision d : ctx.recentDecisions()) {
                p.append("- \"").append(d.title()).append("\": ").append(d.status().toLowerCase(Locale.ROOT));
                if (d.feedback() != null && !d.feedback().isBlank()) p.append(" — \"").append(d.feedback().trim()).append("\"");
                p.append("\n");
            }
        }
        p.append("\n<client_context>\n")
            .append(ctx.clientContext() == null || ctx.clientContext().isBlank() ? "(nothing on record yet)" : ctx.clientContext())
            .append("\n</client_context>\n");
        return p.toString();
    }

    /** The model's brief plus the house rules, whatever the brief says. Only banners and flyers carry words. */
    static String imageBrief(String brief, StandingRunContext ctx, String frame, String headline) {
        String style = or(opt(ctx, "imageStyle"), "Brand photography");
        String rules = switch (style) {
            case "Illustration" -> "Flat editorial illustration.";
            case "Typographic" -> "Bold typographic composition.";
            case "Palette only" -> "Abstract composition of shapes and texture in the brand colours; no objects or people.";
            default -> "Natural, editorial photography.";
        };
        String words = headline != null && !headline.isBlank()
            ? " Render only this headline, spelled exactly, large and legible: \"" + headline.strip() + "\","
                + " set entirely inside the frame with a wide margin on every side, never cropped at an edge. No other text, logos or watermarks."
            : " No text, letters, logos or watermarks.";
        return brief.strip() + "\n\n" + frame + ". " + rules + words + " No real, identifiable people.";
    }

    /** The client's own dates in the window; "MM-DD name" repeats every year. */
    static List<KnownDates.Known> ownDates(StandingRunContext ctx, Window window) {
        List<KnownDates.Known> out = new ArrayList<>();
        for (String line : opt(ctx, "ownDates").split("\\R")) {
            Matcher m = OWN_DATE.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(2).length() > 80 ? m.group(2).substring(0, 80) : m.group(2);
            try {
                if (m.group(1).length() == 10) {
                    LocalDate d = LocalDate.parse(m.group(1));
                    if (window.contains(d)) out.add(new KnownDates.Known(d, name));
                } else {
                    for (int year = window.start().getYear(); year <= window.end().getYear(); year++) {
                        LocalDate d = LocalDate.parse(year + "-" + m.group(1));
                        if (window.contains(d)) out.add(new KnownDates.Known(d, name));
                    }
                }
            } catch (DateTimeParseException ignored) {
                // Not a real date (e.g. 02-30); skip the line.
            }
            if (out.size() >= 20) break;
        }
        return out;
    }

    static List<String> kinds(StandingRunContext ctx) {
        List<String> chosen = pick(opt(ctx, "kinds"), KINDS);
        return chosen.isEmpty() ? KINDS.subList(0, 2) : chosen;
    }

    static Set<String> assets(StandingRunContext ctx) {
        List<String> chosen = pick(opt(ctx, "assets"), ASSETS);
        return new LinkedHashSet<>(chosen.isEmpty() ? List.of("Social post") : chosen);
    }

    static List<String> channels(StandingRunContext ctx) {
        List<String> chosen = pick(opt(ctx, "channels"), CHANNELS);
        return chosen.isEmpty() ? List.of("Instagram", "LinkedIn") : chosen;
    }

    static int perRun(StandingRunContext ctx) {
        return number(ctx, "perRun", 1, 4, 2);
    }

    static int leadDays(StandingRunContext ctx) {
        return number(ctx, "leadDays", 0, 21, 5);
    }

    private static List<String> pick(String value, List<String> allowed) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(allowed::contains).distinct().toList();
    }

    private static int number(StandingRunContext ctx, String key, int min, int max, int fallback) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(opt(ctx, key))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String opt(StandingRunContext ctx, String key) {
        String value = ctx.option(key);
        return value == null ? "" : value.trim();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** From today, in the client's zone, to the chosen number of weeks ahead. */
    record Window(LocalDate start, int weeks) {

        static Window of(StandingRunContext ctx) {
            ZoneId zone = ctx.zone() == null ? ZoneOffset.UTC : ctx.zone();
            Instant now = ctx.now() == null ? Instant.now() : ctx.now();
            return new Window(now.atZone(zone).toLocalDate(), number(ctx, "weeksAhead", 2, 12, 6));
        }

        LocalDate end() {
            return start.plusWeeks(weeks);
        }

        boolean contains(LocalDate d) {
            return d != null && !d.isBefore(start) && !d.isAfter(end());
        }

        /** The lead time before the occasion, but never before today. */
        LocalDate postDay(LocalDate occasion, int leadDays) {
            LocalDate day = occasion.minusDays(leadDays);
            return day.isBefore(start) ? start : day;
        }
    }

    /** Occasions already proposed for this subscription, kept until a while after their date. */
    static final class Memory {

        private final Map<String, Map<String, String>> proposed = new LinkedHashMap<>();

        static Memory parse(String json, LocalDate today) {
            Memory m = new Memory();
            if (json == null || json.isBlank()) return m;
            try {
                for (JsonNode n : JSON.readTree(json).path("proposed")) {
                    LocalDate date = LocalDate.parse(n.path("date").asText(""));
                    if (date.isBefore(today.minusDays(FORGET_AFTER_DAYS))) continue;
                    m.add(key(date, n.path("name").asText("")), n.path("name").asText(""), date);
                }
            } catch (Exception e) {
                // Unreadable memory only means an occasion may be proposed twice.
            }
            return m;
        }

        static String key(LocalDate date, String name) {
            return date + "|" + name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        }

        boolean has(String key) {
            return proposed.containsKey(key);
        }

        void add(String key, String name, LocalDate date) {
            proposed.put(key, Map.of("name", name, "date", date.toString()));
        }

        List<String> inWindow(Window window) {
            return proposed.values().stream().filter(v -> window.contains(LocalDate.parse(v.get("date"))))
                .map(v -> v.get("date") + " " + v.get("name")).toList();
        }

        String toJson() {
            try {
                return JSON.writeValueAsString(Map.of("proposed", List.copyOf(proposed.values())));
            } catch (Exception e) {
                return null;
            }
        }
    }

    record Variant(String channel, String text, List<String> hashtags) {}

    /** One occasion as the model planned it. */
    record Occasion(String name, LocalDate date, String why, String title, String headline, List<Variant> variants,
                    String postImage, String bannerImage, String flyerImage, String flyerText) {

        /** Parses the reply's {@code occasions}; tolerates code fences, skips anything without a real date. */
        static List<Occasion> parseAll(String reply, List<String> channels) {
            if (reply == null) throw new IllegalStateException("The model returned nothing");
            int from = reply.indexOf('{'), to = reply.lastIndexOf('}');
            if (from < 0 || to <= from) throw new IllegalStateException("The model did not return JSON");
            JsonNode root;
            try {
                root = JSON.readTree(reply.substring(from, to + 1));
            } catch (Exception e) {
                throw new IllegalStateException("The model returned unreadable JSON");
            }
            List<Occasion> out = new ArrayList<>();
            for (JsonNode n : root.path("occasions")) {
                String name = n.path("name").asText("").strip();
                LocalDate date;
                try {
                    date = LocalDate.parse(n.path("date").asText("").strip());
                } catch (DateTimeParseException e) {
                    continue;
                }
                if (name.isEmpty()) continue;
                List<Variant> variants = new ArrayList<>();
                for (JsonNode v : n.path("variants")) {
                    String channel = label(v.path("channel").asText(""));
                    String text = v.path("text").asText("").strip();
                    if (channel == null || !channels.contains(channel) || text.isEmpty()) continue;
                    if (variants.stream().anyMatch(x -> x.channel().equals(channel))) continue;
                    List<String> tags = new ArrayList<>();
                    for (JsonNode t : v.path("hashtags")) {
                        String tag = t.asText("").strip().replace(" ", "");
                        if (!tag.isEmpty()) tags.add(tag.startsWith("#") ? tag : "#" + tag);
                    }
                    variants.add(new Variant(channel, text, tags));
                }
                String headline = n.path("headline").asText("").strip();
                if (headline.split("\\s+").length > 8) headline = "";
                out.add(new Occasion(name, date, n.path("why").asText("").strip(), n.path("title").asText("").strip(), headline,
                    variants, n.path("postImage").asText("").strip(), n.path("bannerImage").asText("").strip(),
                    n.path("flyerImage").asText("").strip(), n.path("flyerText").asText("").strip()));
            }
            return out;
        }

        Map<String, Object> occasionPayload() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("date", date.toString());
            if (!why.isEmpty()) m.put("why", why);
            return m;
        }

        StandingRunResult.Item toPost(LocalDate proposedFor, String format, List<StandingRunResult.Attachment> files) {
            List<Map<String, Object>> payloadVariants = new ArrayList<>();
            StringBuilder body = new StringBuilder();
            for (Variant v : variants) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("channel", v.channel().toLowerCase(Locale.ROOT));
                m.put("label", v.channel());
                m.put("text", v.text());
                m.put("hashtags", v.hashtags());
                payloadVariants.add(m);
                body.append("## ").append(v.channel()).append("\n\n").append(v.text()).append("\n");
                if (!v.hashtags().isEmpty()) body.append("\n").append(String.join(" ", v.hashtags())).append("\n");
                body.append("\n");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("variants", payloadVariants);
            payload.put("format", format);
            payload.put("pillar", "Occasion");
            payload.put("proposedFor", proposedFor.toString());
            payload.put("occasion", occasionPayload());
            String head = name + " · " + DAY.format(date) + " · post on " + DAY.format(proposedFor);
            String heading = title.isEmpty() ? name : name + ": " + title;
            return new StandingRunResult.Item(StandingRunResult.Item.DRAFT_POST, clip(heading, 120),
                why.isEmpty() ? head : clip(why, 300), "_" + head + "_\n\n" + body.toString().strip(), payload, files);
        }

        StandingRunResult.Item toAssets(List<StandingRunResult.Attachment> files, List<Map<String, Object>> made) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("occasion", occasionPayload());
            payload.put("assets", made);
            if (!headline.isEmpty()) payload.put("headline", headline);
            if (!flyerText.isEmpty()) payload.put("copy", flyerText);
            String labels = String.join(", ", made.stream().map(m -> String.valueOf(m.get("label")).toLowerCase(Locale.ROOT)).toList());
            StringBuilder body = new StringBuilder("For ").append(name).append(", ").append(DAY.format(date)).append(".");
            if (!headline.isEmpty()) body.append("\n\nHeadline: ").append(headline);
            if (!flyerText.isEmpty()) body.append("\n\nCopy: ").append(flyerText);
            return new StandingRunResult.Item(StandingRunResult.Item.ASSET,
                clip(Character.toUpperCase(labels.charAt(0)) + labels.substring(1) + " for " + name, 120),
                "For " + DAY.format(date) + ". Download it for your website, shop window or print.",
                body.toString(), payload, files);
        }

        private static String label(String channel) {
            for (String c : CHANNELS) if (c.equalsIgnoreCase(channel.strip())) return c;
            return null;
        }

        private static String clip(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max - 1) + "…";
        }
    }
}
