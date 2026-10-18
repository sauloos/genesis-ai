package ai.genesisbrands.platform;

import java.util.List;

/**
 * A single widget-declared config knob, rendered generically by the Page Flow builder's
 * layout editor. Same shape as AgentCustomOption, kept separate so widget config can
 * evolve independently of agent config.
 */
public record WidgetConfigOption(
    String key,
    String label,
    OptionType type,
    List<String> allowedValues,
    String defaultValue
) {

    public enum OptionType {
        STRING, BOOLEAN, SELECT
    }
}
