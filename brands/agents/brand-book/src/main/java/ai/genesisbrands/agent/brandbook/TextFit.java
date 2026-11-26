package ai.genesisbrands.agent.brandbook;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shortens copy for the brand book's fixed slots without cutting words or sentences in half.
 * A brand book that shows "the community who…" mid-thought reads as a broken template, so the
 * order of preference is: whole sentences, then a whole clause, then whole words with an ellipsis.
 */
final class TextFit {

    private TextFit() {}

    /**
     * A sentence ends at . ! or ? followed by whitespace and a capital, digit or opening quote.
     * That skips decimals ("2.5"), and most abbreviations ("e.g. our"), which a bare indexOf('.')
     * split on.
     */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?][\"'”’)]?)\\s+(?=[\"'“‘(]?[A-Z0-9])");

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String flat = text.replaceAll("\\s+", " ").strip();
        int start = 0;
        Matcher m = SENTENCE_END.matcher(flat);
        while (m.find()) {
            String s = flat.substring(start, m.start()).strip();
            if (!s.isEmpty()) out.add(s);
            start = m.end();
        }
        String tail = flat.substring(start).strip();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    /**
     * As many whole sentences as fit in {@code maxChars}. When even the first sentence is longer,
     * the first clause that fits (cut at a comma, semicolon, colon or dash) if it carries at least
     * half the budget; otherwise whole words and an ellipsis.
     */
    static String fit(String text, int maxChars) {
        List<String> all = sentences(text);
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : all) {
            int next = sb.length() == 0 ? s.length() : sb.length() + 1 + s.length();
            if (next > maxChars) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        if (sb.length() > 0) return sb.toString();
        return clip(all.get(0), maxChars);
    }

    /**
     * Whole sentences from the start until the snippet reads as a thought (at least
     * {@code minChars}), never past {@code maxChars} unless the first sentence alone is longer, in
     * which case that sentence is clipped by {@link #clip}.
     */
    static String snippet(String text, int minChars, int maxChars) {
        List<String> all = sentences(text);
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : all) {
            int next = sb.length() == 0 ? s.length() : sb.length() + 1 + s.length();
            if (next > maxChars) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
            if (sb.length() >= minChars) break;
        }
        if (sb.length() > 0) return sb.toString();
        return clip(all.get(0), maxChars);
    }

    private static final Pattern CLAUSE_BREAK = Pattern.compile("[,;:]\\s|\\s[—–-]\\s");

    /** One over-long sentence cut at a clause, or at a word with an ellipsis. */
    static String clip(String sentence, int maxChars) {
        String s = sentence.strip();
        if (s.length() <= maxChars) return s;
        int bestClause = -1;
        Matcher m = CLAUSE_BREAK.matcher(s);
        while (m.find() && m.start() <= maxChars - 1) bestClause = m.start();
        if (bestClause >= maxChars / 2) {
            return s.substring(0, bestClause).strip() + "…";
        }
        int space = s.lastIndexOf(' ', maxChars - 1);
        String cut = space > 0 ? s.substring(0, space) : s.substring(0, maxChars - 1);
        return cut.replaceAll("[\\s,;:—–-]+$", "") + "…";
    }
}
