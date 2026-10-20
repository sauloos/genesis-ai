package ai.genesisbrands.agent.content;

import ai.genesisbrands.platform.standing.PostPerformance.Metrics;
import ai.genesisbrands.platform.standing.PostPerformance.PublishedPost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What performed among the client's published posts, turned into weights for the next batch.
 * Each post is scored against the average engagement on its own network (1.0 = average), so
 * a LinkedIn post and an Instagram post compare fairly. Topics (pillars) and formats are then
 * ranked by their mean score, and the batch's posts are shared out among the chosen topics in
 * proportion to it. A topic with no measured posts yet counts as average, so it keeps being
 * tried; no topic's weight drops below a quarter of average.
 */
record ContentPerformance(String section, int measured, Map<String, Integer> mix) {

    static final int MIN_MEASURED = 2;
    private static final double FLOOR = 0.25;

    /** Null when fewer than {@link #MIN_MEASURED} published posts have figures yet. */
    static ContentPerformance of(List<PublishedPost> published, List<String> pillars, List<String> formats, int posts) {
        List<PublishedPost> measured = published == null ? List.of()
            : published.stream().filter(p -> p.metrics() != null).toList();
        if (measured.size() < MIN_MEASURED) return null;

        Map<String, double[]> perNetwork = new HashMap<>();
        for (PublishedPost p : measured) {
            double[] sum = perNetwork.computeIfAbsent(network(p), k -> new double[2]);
            sum[0] += p.metrics().engagements();
            sum[1]++;
        }
        Map<PublishedPost, Double> score = new LinkedHashMap<>();
        for (PublishedPost p : measured) {
            double[] sum = perNetwork.get(network(p));
            double mean = sum[0] / sum[1];
            score.put(p, mean <= 0 ? 1.0 : p.metrics().engagements() / mean);
        }

        Map<String, Group> byPillar = group(score, "pillar", ContentAgent.PILLARS);
        Map<String, Group> byFormat = group(score, "format", ContentAgent.FORMATS);
        Map<String, Integer> mix = mix(pillars, byPillar, posts);

        StringBuilder s = new StringBuilder();
        s.append("What performed: your posts the client published in the last ").append(ContentAgent.PERFORMANCE_DAYS)
            .append(" days, ").append(measured.size()).append(" with figures. 1.0× is the client's average engagement")
            .append(" (reactions, comments, shares, saves, clicks) on that network.\n");
        s.append("Topics:\n");
        for (String pillar : pillars) s.append(line(pillar, byPillar.get(pillar)));
        s.append("Formats:\n");
        for (String format : formats) s.append(line(format, byFormat.get(format)));
        List<PublishedPost> best = score.entrySet().stream()
            .sorted(Map.Entry.<PublishedPost, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(3).map(Map.Entry::getKey).toList();
        s.append("Best posts:\n");
        for (PublishedPost p : best) {
            s.append("- \"").append(p.title()).append("\" (").append(label(p)).append(String.format(Locale.ROOT, ", %.1f×", score.get(p)))
                .append(": ").append(figures(p.metrics())).append(")\n");
        }
        s.append("Weight this batch toward what performs. Aim for this topic mix: ");
        List<String> parts = new ArrayList<>();
        mix.forEach((pillar, n) -> { if (n > 0) parts.add(n + " " + pillar); });
        s.append(String.join(", ", parts)).append(". Among the chosen formats, prefer the ones above 1.0×, and learn")
            .append(" from what the best posts did (angle, hook, length), without copying them.\n");
        return new ContentPerformance(s.toString(), measured.size(), mix);
    }

    /** The batch's posts shared out by largest remainder over each chosen topic's weight. */
    static Map<String, Integer> mix(List<String> pillars, Map<String, Group> byPillar, int posts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (pillars.isEmpty() || posts <= 0) return out;
        double[] weight = new double[pillars.size()];
        double total = 0;
        for (int i = 0; i < pillars.size(); i++) {
            Group g = byPillar.get(pillars.get(i));
            weight[i] = Math.max(FLOOR, g == null ? 1.0 : g.mean());
            total += weight[i];
        }
        int[] count = new int[pillars.size()];
        double[] remainder = new double[pillars.size()];
        int given = 0;
        for (int i = 0; i < pillars.size(); i++) {
            double share = posts * weight[i] / total;
            count[i] = (int) Math.floor(share);
            remainder[i] = share - count[i];
            given += count[i];
        }
        while (given < posts) {
            int best = 0;
            for (int i = 1; i < pillars.size(); i++) {
                if (remainder[i] > remainder[best] || (remainder[i] == remainder[best] && weight[i] > weight[best])) best = i;
            }
            count[best]++;
            remainder[best] = -1;
            given++;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < pillars.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> -weight[i]));
        for (int i : order) out.put(pillars.get(i), count[i]);
        return out;
    }

    record Group(int posts, double mean) {}

    private static Map<String, Group> group(Map<PublishedPost, Double> score, String key, List<String> known) {
        Map<String, double[]> sums = new HashMap<>();
        score.forEach((p, s) -> {
            String value = canonical(p.payload() == null ? null : p.payload().get(key), known);
            if (value == null) return;
            double[] sum = sums.computeIfAbsent(value, k -> new double[2]);
            sum[0] += s;
            sum[1]++;
        });
        Map<String, Group> out = new HashMap<>();
        sums.forEach((k, v) -> out.put(k, new Group((int) v[1], v[0] / v[1])));
        return out;
    }

    private static String canonical(Object value, List<String> known) {
        if (value == null) return null;
        String v = String.valueOf(value).strip();
        for (String k : known) if (k.equalsIgnoreCase(v)) return k;
        return null;
    }

    private static String line(String name, Group g) {
        if (g == null) return "- " + name + ": nothing measured yet\n";
        return String.format(Locale.ROOT, "- %s: %.1f× over %d post%s\n", name, g.mean(), g.posts(), g.posts() == 1 ? "" : "s");
    }

    private static String network(PublishedPost p) {
        return p.network() == null ? "" : p.network().toLowerCase(Locale.ROOT);
    }

    private static String label(PublishedPost p) {
        String n = network(p);
        return n.isEmpty() ? "post" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    static String figures(Metrics m) {
        List<String> parts = new ArrayList<>();
        add(parts, m.reactions(), "reaction");
        add(parts, m.comments(), "comment");
        add(parts, m.shares(), "share");
        add(parts, m.saves(), "save");
        add(parts, m.clicks(), "click");
        if (m.reach() != null) parts.add("reach " + m.reach());
        else if (m.impressions() != null) parts.add(m.impressions() + " views");
        return parts.isEmpty() ? "no figures" : String.join(", ", parts);
    }

    private static void add(List<String> parts, Long value, String noun) {
        if (value != null) parts.add(value + " " + noun + (value == 1 ? "" : "s"));
    }
}
