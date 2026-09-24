package ai.genesisbrands.platform;

import java.util.List;

/**
 * Extension point: a tenant (or core) registers a widget type so the Page Flow
 * builder's layout editor can discover it generically. Mirrors CoreAgent — a plain
 * @Component bean, auto-collected via List<WidgetDescriptor>, no registry wiring.
 */
public interface WidgetDescriptor {

    String widgetType();

    String displayName();

    String description();

    default List<WidgetConfigOption> configOptions() {
        return List.of();
    }

    /** Named exits this widget type can produce. Defaults to a single implicit "next". */
    default List<WidgetOutcome> outcomes() {
        return List.of(WidgetOutcome.DEFAULT);
    }
}
