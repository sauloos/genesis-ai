package ai.genesisbrands.platform;

import java.util.List;

/**
 * A single agent-declared runtime knob, rendered generically by the Playground
 * (e.g. the Logo agent's underlying image-generation method) and by the standing-agent
 * subscription form. Not a full JSON-Schema — just enough shape to drive a simple form.
 * <p>
 * Values travel as strings: a {@code MULTI_SELECT} value is comma-separated, a {@code NUMBER}
 * is a decimal integer whose bounds, when it has any, are {@code allowedValues = [min, max]}.
 */
public record AgentCustomOption(
    String key,
    String label,
    OptionType type,
    List<String> allowedValues,
    String defaultValue,
    String helpText
) {

    public AgentCustomOption(String key, String label, OptionType type, List<String> allowedValues, String defaultValue) {
        this(key, label, type, allowedValues, defaultValue, null);
    }

    public enum OptionType {
        /** One line of free text. */
        STRING,
        BOOLEAN,
        /** Exactly one of {@code allowedValues}. */
        SELECT,
        /** Any subset of {@code allowedValues}, comma-separated. */
        MULTI_SELECT,
        /** An integer, bounded by {@code allowedValues = [min, max]} when given. */
        NUMBER,
        /** Several lines of free text (notes, things to avoid). */
        TEXT
    }
}
