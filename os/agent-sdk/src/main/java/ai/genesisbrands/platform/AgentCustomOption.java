package ai.genesisbrands.platform;

import java.util.List;

/**
 * A single agent-declared runtime knob, rendered generically by the Playground
 * (e.g. the Logo agent's underlying image-generation method). Not a full JSON-Schema —
 * just enough shape to drive a simple form.
 */
public record AgentCustomOption(
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
