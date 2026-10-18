package ai.genesisbrands.platform;

/**
 * A named exit a widget can produce (e.g. "next", or "onSuccess"/"onFailure" for a
 * widget with branching results). The Page Flow builder canvas renders one output
 * port per distinct outcome a page's placed widgets declare.
 */
public record WidgetOutcome(String key, String label) {

    public static final WidgetOutcome DEFAULT = new WidgetOutcome("next", "Next");
}
