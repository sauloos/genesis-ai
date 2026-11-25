package ai.genesisbrands.agent.logo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The creative director's assessment of the logo a client already has (Layer 1
 * {@code evaluation/existing-logo-audit.yaml}): a score per criterion, an overall score out of
 * 100, and a KEEP / EVOLVE / REPLACE recommendation with what carries over. Written to the client,
 * who reads it before choosing what happens to their logo.
 *
 * <p>The overall score is computed here from the criteria and their weights, never taken from
 * the model, so it always agrees with the scores the client sees.
 */
public record LogoAssessment(
    int score,
    String verdict,
    String headline,
    String summary,
    String description,
    String reading,
    List<Criterion> criteria,
    List<String> strengths,
    List<String> weaknesses,
    List<String> keep,
    List<String> change,
    String confidence,
    String confidenceNote
) {
    public static final String KEEP = "KEEP";
    public static final String EVOLVE = "EVOLVE";
    public static final String REPLACE = "REPLACE";

    public record Criterion(String key, String label, String weight, int score, String note) {}

    /** critical 3, high 2, anything else 1 — as the module defines. */
    static int weight(String weight) {
        String w = weight == null ? "" : weight.strip().toLowerCase(Locale.ROOT);
        return switch (w) {
            case "critical" -> 3;
            case "high" -> 2;
            default -> 1;
        };
    }

    /** The weighted mean of the criteria's 0–10 scores, as a score out of 100. */
    static int overall(List<Criterion> criteria) {
        int weights = 0;
        int sum = 0;
        for (Criterion c : criteria) {
            int w = weight(c.weight());
            weights += w;
            sum += w * c.score();
        }
        return weights == 0 ? 0 : Math.round(sum * 10f / weights);
    }

    /** Reads the audit's JSON (already parsed to a map); throws when it has no usable criteria. */
    static LogoAssessment from(Map<String, Object> map) {
        List<Criterion> criteria = new ArrayList<>();
        if (map.get("criteria") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || !(m.get("score") instanceof Number n)) continue;
                criteria.add(new Criterion(str(m.get("key")), str(m.get("label")), str(m.get("weight")),
                    Math.max(0, Math.min(10, (int) Math.round(n.doubleValue()))), str(m.get("note"))));
            }
        }
        if (criteria.isEmpty()) throw new IllegalStateException("The logo assessment scored nothing");
        String verdict = str(map.get("verdict"));
        verdict = verdict == null ? "" : verdict.strip().toUpperCase(Locale.ROOT);
        if (!verdict.equals(KEEP) && !verdict.equals(EVOLVE) && !verdict.equals(REPLACE)) {
            throw new IllegalStateException("The logo assessment gave no verdict");
        }
        String confidence = str(map.get("confidence"));
        return new LogoAssessment(overall(criteria), verdict, str(map.get("headline")), str(map.get("summary")),
            str(map.get("description")), str(map.get("reading")), List.copyOf(criteria),
            strings(map.get("strengths")), strings(map.get("weaknesses")), strings(map.get("keep")),
            strings(map.get("change")),
            confidence == null ? "HIGH" : confidence.strip().toUpperCase(Locale.ROOT).split("[^A-Z]")[0],
            str(map.get("confidenceNote")));
    }

    /** What the logo agents read: the verdict and what carries over, for the EVOLVED direction. */
    public String renderForDesigner() {
        StringBuilder sb = new StringBuilder("## The client's current logo (assessed)\n");
        sb.append("What it is: ").append(description).append('\n');
        sb.append("Score: ").append(score).append("/100; recommendation: ").append(verdict).append('\n');
        if (!keep.isEmpty()) sb.append("Carry over: ").append(String.join("; ", keep)).append('\n');
        if (!change.isEmpty()) sb.append("Must change: ").append(String.join("; ", change)).append('\n');
        return sb.toString();
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        return list.stream().map(LogoAssessment::str).filter(s -> s != null && !s.isBlank()).toList();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().strip();
        return s.isEmpty() || s.equals("null") ? null : s;
    }
}
