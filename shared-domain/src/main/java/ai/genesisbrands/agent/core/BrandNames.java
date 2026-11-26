package ai.genesisbrands.agent.core;

import java.util.regex.Pattern;

/**
 * The brand name as the brand uses it, not as it is registered. Clients often type the registered
 * name ("One Way Coffee LTD"); a legal suffix belongs in legal lines only, never in a wordmark, a
 * headline or the brand book's running text. The direction-brief prompt already asks for the
 * trading name; this makes it hold even when the model copies the registered form.
 */
public final class BrandNames {

    private static final Pattern LEGAL_SUFFIX = Pattern.compile(
        "(?i)[\\s,]+(ltd\\.?|limited|llc|l\\.l\\.c\\.|inc\\.?|incorporated|plc|gmbh|s\\.?a\\.?|"
            + "pty\\.? ltd\\.?|co\\.? ltd\\.?|corp\\.?|ltda\\.?|b\\.?v\\.?|ag)\\s*$");

    private BrandNames() {}

    /** The name without a trailing legal suffix; null and blank pass through unchanged. */
    public static String tradingName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String out = name.strip();
        String previous;
        do {
            previous = out;
            out = LEGAL_SUFFIX.matcher(out).replaceFirst("").strip();
        } while (!out.equals(previous) && !out.isEmpty());
        return out.isEmpty() ? name.strip() : out;
    }
}
