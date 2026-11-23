package ai.genesisbrands.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a question applies, given the answers so far.
 *
 * <p>The grammar is one clause and deliberately stays that way:
 *
 * <pre>
 *   belong.buyerIsUser == 'no'
 *   look.logoIntent != 'keep'
 *   beyond.sdgs contains 'climate-action'
 *   look.currentLogo is present
 *   look.currentLogo is absent
 * </pre>
 *
 * <p>No boolean operators, no nesting. A skip rule that cannot be said in one clause is a
 * sign the questionnaire wants a different shape, not that the language wants features — and
 * a language small enough to parse in thirty lines is one that behaves the same on the server
 * and in a client's head. Values compare case-insensitively after trimming.
 *
 * <p>Two failure directions matter and they are not symmetric. An unparseable or unknown
 * condition makes the question <em>apply</em>: showing a client one question too many is
 * recoverable, whereas silently hiding a required one yields a brief with a hole in it that
 * nothing downstream can detect. Authoring validation is therefore where a bad condition gets
 * caught — {@link #parse(String)} throws there, and the save is rejected.
 */
public final class QuestionCondition {

    private static final Pattern CLAUSE = Pattern.compile(
        "^\\s*([A-Za-z][A-Za-z0-9_.\\[\\]]*)\\s*"
            + "(?:(==|!=|contains)\\s*(?:'([^']*)'|\"([^\"]*)\")|is\\s+(present|absent))\\s*$",
        Pattern.CASE_INSENSITIVE);

    private QuestionCondition() {
    }

    public enum Op { EQUALS, NOT_EQUALS, CONTAINS, PRESENT, ABSENT }

    /** One parsed clause. {@code value} is null for {@link Op#PRESENT}/{@link Op#ABSENT}. */
    public record Clause(String signalKey, Op op, String value) {
    }

    /**
     * Parses a condition for authoring-time validation.
     *
     * @return empty when the expression is blank, meaning "always applies"
     * @throws IllegalArgumentException when it is not blank and not a valid clause
     */
    public static Optional<Clause> parse(String expr) {
        if (expr == null || expr.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CLAUSE.matcher(expr);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                "Not a valid condition: \"" + expr + "\". Expected one of: "
                    + "<signalKey> == 'value' | != 'value' | contains 'value' | is present | is absent");
        }
        String signalKey = m.group(1);
        String operator = m.group(2);
        if (operator == null) {
            boolean present = "present".equalsIgnoreCase(m.group(5));
            return Optional.of(new Clause(signalKey, present ? Op.PRESENT : Op.ABSENT, null));
        }
        String value = m.group(3) != null ? m.group(3) : m.group(4);
        Op op = switch (operator.toLowerCase()) {
            case "==" -> Op.EQUALS;
            case "!=" -> Op.NOT_EQUALS;
            default -> Op.CONTAINS;
        };
        return Optional.of(new Clause(signalKey, op, value));
    }

    /**
     * Evaluates a condition against answers keyed by signal key.
     *
     * <p>{@code ==} is "the answer is exactly this" — for a multi-select that means a single
     * selected value. {@code contains} is "the answer includes this", which is the form to
     * use against a multi-select. Neither does substring matching: elements compare whole,
     * so {@code contains 'art'} never fires on "smart".
     *
     * @return true when the question applies, including when {@code expr} is blank or invalid
     */
    public static boolean applies(String expr, Map<String, Object> signals) {
        Clause clause;
        try {
            var parsed = parse(expr);
            if (parsed.isEmpty()) {
                return true;
            }
            clause = parsed.get();
        } catch (IllegalArgumentException e) {
            return true; // fail toward showing — see the class comment
        }

        Object answer = signals == null ? null : signals.get(clause.signalKey());
        boolean present = isPresent(answer);

        return switch (clause.op()) {
            case PRESENT -> present;
            case ABSENT -> !present;
            case EQUALS -> present && matchesExactly(answer, clause.value());
            case NOT_EQUALS -> !present || !matchesExactly(answer, clause.value());
            case CONTAINS -> present && containsValue(answer, clause.value());
        };
    }

    /** An answer that was skipped, cleared, or left empty counts as no answer at all. */
    static boolean isPresent(Object answer) {
        if (answer == null) {
            return false;
        }
        if (answer instanceof CharSequence s) {
            return !s.toString().isBlank();
        }
        if (answer instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (answer instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private static boolean matchesExactly(Object answer, String value) {
        if (answer instanceof Collection<?> c) {
            return c.size() == 1 && equalsLoosely(c.iterator().next(), value);
        }
        return equalsLoosely(answer, value);
    }

    private static boolean containsValue(Object answer, String value) {
        if (answer instanceof Collection<?> c) {
            return c.stream().anyMatch(element -> equalsLoosely(element, value));
        }
        return equalsLoosely(answer, value);
    }

    private static boolean equalsLoosely(Object answer, String value) {
        return answer != null && String.valueOf(answer).trim().equalsIgnoreCase(value.trim());
    }
}
