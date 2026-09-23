package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A minimal heading + body block. Ships alongside RedirectWidget as a second widget
 * type so the layout editor has more than one type to place, order, and configure.
 */
@Component
public class ContentWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "content";
    }

    @Override
    public String displayName() {
        return "Content";
    }

    @Override
    public String description() {
        return "A simple heading and body text block.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("heading", "Heading", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("body", "Body", OptionType.STRING, List.of(), "")
        );
    }
}
