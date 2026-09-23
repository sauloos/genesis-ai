package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Given a target URL, immediately redirects — lets a trivial PageFlow (e.g. "/") just
 * forward to another PageFlow's slug (e.g. "/discover") without duplicating pages.
 */
@Component
public class RedirectWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "redirect";
    }

    @Override
    public String displayName() {
        return "Redirect";
    }

    @Override
    public String description() {
        return "Immediately redirects the visitor to a target URL.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("targetUrl", "Target URL", OptionType.STRING, List.of(), "")
        );
    }
}
