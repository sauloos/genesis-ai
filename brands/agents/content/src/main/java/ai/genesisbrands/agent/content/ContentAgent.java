package ai.genesisbrands.agent.content;

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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Genesis Brands standing agent: each run plans and drafts a small batch of social posts in the
 * client's brand (per-channel copy plus images) into the client's inbox. It only drafts; the
 * client approves, then copies or downloads each post. Publishing to channels is a later phase
 * (docs/standing-agents-plan.md). Tenant-specific because it is grounded in the brand book the
 * brand engagement produced.
 */
@Component
public class ContentAgent implements CoreAgent, StandingAgent {

    static final String ID = "content";
    static final List<String> CHANNELS = List.of("Instagram", "Facebook", "LinkedIn");
    static final List<String> FORMATS = List.of("Single image", "Carousel", "Story", "Text only");
    static final List<String> PILLARS = List.of("Education", "Behind the scenes", "Product", "Community", "Inspiration", "Announcements");
    static final List<String> IMAGE_STYLES = List.of("Brand photography", "Illustration", "Typographic", "Palette only", "No images");
    private static final int MAX_FRAMES = 3;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM yyyy", Locale.ENGLISH);
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA, JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final ContentImages images;

    public ContentAgent(ContentImages images) {
        this.images = images;
    }

    @Override
    public String agentId() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Content";
    }

    @Override
    public String description() {
        return "Social posts in your brand's voice and colours, drafted for you on a schedule, ready to approve.";
    }

    @Override
    public String icon() {
        return "✍️";
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
    public List<AgentCustomOption> subscriptionOptions() {
        return List.of(
            new AgentCustomOption("channels", "Channels", OptionType.MULTI_SELECT, CHANNELS, "Instagram,LinkedIn",
                "Each post is written once per channel."),
            new AgentCustomOption("posts", "Posts per run", OptionType.NUMBER, List.of("1", "7"), "3", null),
            new AgentCustomOption("formats", "Formats", OptionType.MULTI_SELECT, FORMATS, "Single image,Carousel", null),
            new AgentCustomOption("pillars", "Topics", OptionType.MULTI_SELECT, PILLARS, "Education,Behind the scenes,Product",
                "The kinds of posts to rotate between."),
            new AgentCustomOption("topicNotes", "What to talk about", OptionType.TEXT, List.of(), "",
                "Launches, events, seasonal moments, themes for this period."),
            new AgentCustomOption("style", "Writing style", OptionType.SELECT,
                List.of("Brand voice", "Friendly", "Bold", "Expert", "Playful"), "Brand voice",
                "Brand voice follows the tone guide from your brand book."),
            new AgentCustomOption("imageStyle", "Images", OptionType.SELECT, IMAGE_STYLES, "Brand photography", null),
            new AgentCustomOption("language", "Language", OptionType.SELECT,
                List.of("English", "Portuguese", "Spanish", "French", "German"), "English", null),
            new AgentCustomOption("avoid", "Avoid", OptionType.TEXT, List.of(), "",
                "Topics, words or claims to stay away from."));
    }

    @Override
    public StandingRunResult run(StandingRunContext ctx) {
        Period period = Period.of(ctx);
        List<Post> posts = Post.parseAll(ctx.model().complete(prompt(ctx, period)), channels(ctx));
        if (posts.isEmpty()) throw new IllegalStateException("The model returned no usable posts");
        int limit = postCount(ctx);
        boolean drawImages = !"No images".equals(opt(ctx, "imageStyle")) && images.available();
        List<StandingRunResult.Item> items = new ArrayList<>();
        int imageCount = 0;
        for (Post post : posts.subList(0, Math.min(limit, posts.size()))) {
            List<StandingRunResult.Attachment> files = new ArrayList<>();
            if (drawImages && !"Text only".equals(post.format())) {
                String size = "Story".equals(post.format()) ? "1024x1536" : "1024x1024";
                int frame = 1;
                for (String brief : post.images()) {
                    byte[] png = images.render(imageBrief(brief, ctx), size);
                    if (png != null) files.add(new StandingRunResult.Attachment("frame-" + frame + ".png", "image/png", png));
                    frame++;
                }
                imageCount += files.size();
            }
            items.add(post.toItem(period.dayOf(post.day()), files));
        }
        String note = items.size() + (items.size() == 1 ? " post" : " posts") + ", " + imageCount
            + (imageCount == 1 ? " image" : " images");
        return new StandingRunResult(items, note);
    }

    static String prompt(StandingRunContext ctx, Period period) {
        StringBuilder p = new StringBuilder();
        p.append("Client: ").append(ctx.clientName()).append("\n");
        p.append("This batch covers ").append(period.days()).append(period.days() == 1 ? " day" : " days")
            .append(", starting ").append(DAY.format(period.start())).append(" (day 1).\n\n");
        p.append("Write ").append(postCount(ctx)).append(postCount(ctx) == 1 ? " post" : " posts").append(".\n");
        p.append("Channels: ").append(String.join(", ", channels(ctx))).append("\n");
        p.append("Formats: ").append(list(ctx, "formats", FORMATS.subList(0, 2))).append("\n");
        p.append("Pillars: ").append(list(ctx, "pillars", PILLARS.subList(0, 3))).append("\n");
        p.append("Writing style: ").append(or(opt(ctx, "style"), "Brand voice")).append("\n");
        p.append("Image style: ").append(or(opt(ctx, "imageStyle"), "Brand photography")).append("\n");
        p.append("Write in: ").append(or(opt(ctx, "language"), "English")).append("\n");
        if (!opt(ctx, "topicNotes").isBlank()) p.append("The client wants to talk about: ").append(opt(ctx, "topicNotes")).append("\n");
        if (!opt(ctx, "avoid").isBlank()) p.append("Avoid: ").append(opt(ctx, "avoid")).append("\n");
        if (!ctx.recentDecisions().isEmpty()) {
            p.append("\nThe client's recent posts and what they did with them (do not repeat these; follow their feedback):\n");
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

    /** The model's brief plus the house rules every frame needs, whatever the brief says. */
    static String imageBrief(String brief, StandingRunContext ctx) {
        String style = or(opt(ctx, "imageStyle"), "Brand photography");
        String rules = switch (style) {
            case "Illustration" -> "Flat editorial illustration.";
            case "Typographic" -> "Bold typographic composition; render only the quoted words, spelled exactly.";
            case "Palette only" -> "Abstract composition of shapes and texture in the brand colours; no objects or people.";
            default -> "Natural, editorial photography.";
        };
        String noText = "Typographic".equals(style) ? "" : " No text, letters, logos or watermarks.";
        return brief.strip() + "\n\n" + rules + noText + " No real, identifiable people. Square-safe composition for social media.";
    }

    static int postCount(StandingRunContext ctx) {
        try {
            return Math.max(1, Math.min(7, Integer.parseInt(opt(ctx, "posts"))));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    static List<String> channels(StandingRunContext ctx) {
        List<String> chosen = Arrays.stream(opt(ctx, "channels").split(","))
            .map(String::strip).filter(CHANNELS::contains).toList();
        return chosen.isEmpty() ? List.of("Instagram", "LinkedIn") : chosen;
    }

    private static String list(StandingRunContext ctx, String key, List<String> fallback) {
        String value = opt(ctx, key);
        return value.isBlank() ? String.join(", ", fallback) : value.replace(",", ", ");
    }

    private static String opt(StandingRunContext ctx, String key) {
        String value = ctx.option(key);
        return value == null ? "" : value.trim();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The days a batch covers: from today to the next run in the client's zone, 1 to 31 days. */
    record Period(LocalDate start, int days) {

        static Period of(StandingRunContext ctx) {
            ZoneId zone = ctx.zone() == null ? ZoneOffset.UTC : ctx.zone();
            Instant now = ctx.now() == null ? Instant.now() : ctx.now();
            int days = 7;
            if (ctx.nextRunAt() != null && ctx.nextRunAt().isAfter(now)) {
                days = (int) Math.max(1, Math.min(31, Math.round(Duration.between(now, ctx.nextRunAt()).toHours() / 24.0)));
            }
            return new Period(now.atZone(zone).toLocalDate(), days);
        }

        /** Day 1 is the start; out-of-range days are clamped into the period. */
        LocalDate dayOf(int day) {
            return start.plusDays(Math.max(0, Math.min(days - 1, day - 1)));
        }
    }

    record Variant(String channel, String text, List<String> hashtags) {}

    /** One post as the model planned it. */
    record Post(String title, String summary, String pillar, String format, int day, List<Variant> variants,
                List<String> images) {

        /** Parses the reply's {@code posts}, keeping only the chosen channels; tolerates code fences. */
        static List<Post> parseAll(String reply, List<String> channels) {
            if (reply == null) return List.of();
            int from = reply.indexOf('{'), to = reply.lastIndexOf('}');
            if (from < 0 || to <= from) return List.of();
            JsonNode root;
            try {
                root = JSON.readTree(reply.substring(from, to + 1));
            } catch (Exception e) {
                return List.of();
            }
            List<Post> posts = new ArrayList<>();
            for (JsonNode n : root.path("posts")) {
                List<Variant> variants = new ArrayList<>();
                for (JsonNode v : n.path("variants")) {
                    String channel = label(v.path("channel").asText(""));
                    String text = v.path("text").asText("").strip();
                    if (channel == null || !channels.contains(channel) || text.isEmpty()) continue;
                    if (variants.stream().anyMatch(x -> x.channel().equals(channel))) continue;
                    List<String> tags = new ArrayList<>();
                    for (JsonNode t : v.path("hashtags")) {
                        String tag = t.asText("").strip().replace(" ", "");
                        if (tag.isEmpty()) continue;
                        tags.add(tag.startsWith("#") ? tag : "#" + tag);
                    }
                    variants.add(new Variant(channel, text, tags));
                }
                if (variants.isEmpty()) continue;
                String format = FORMATS.contains(n.path("format").asText("")) ? n.path("format").asText() : "Single image";
                List<String> briefs = new ArrayList<>();
                int frames = "Carousel".equals(format) ? MAX_FRAMES : 1;
                for (JsonNode b : n.path("images")) {
                    if (briefs.size() >= frames) break;
                    if (!b.asText("").isBlank()) briefs.add(b.asText().strip());
                }
                if ("Text only".equals(format)) briefs.clear();
                String title = n.path("title").asText("").strip();
                posts.add(new Post(title.isEmpty() ? firstLine(variants.get(0).text()) : title,
                    n.path("summary").asText("").strip(), n.path("pillar").asText("").strip(), format,
                    n.path("day").asInt(1), variants, briefs));
            }
            return posts;
        }

        StandingRunResult.Item toItem(LocalDate proposedFor, List<StandingRunResult.Attachment> files) {
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
            if (!pillar.isEmpty()) payload.put("pillar", pillar);
            payload.put("proposedFor", proposedFor.toString());
            String head = format + (pillar.isEmpty() ? "" : " · " + pillar) + " · for " + DAY.format(proposedFor);
            return new StandingRunResult.Item(StandingRunResult.Item.DRAFT_POST, title,
                summary.isEmpty() ? head : summary, "_" + head + "_\n\n" + body.toString().strip(), payload, files);
        }

        private static String label(String channel) {
            for (String c : CHANNELS) if (c.equalsIgnoreCase(channel.strip())) return c;
            return null;
        }

        private static String firstLine(String text) {
            String line = text.lines().findFirst().orElse("").strip();
            return line.length() > 70 ? line.substring(0, 67) + "..." : line;
        }
    }
}
