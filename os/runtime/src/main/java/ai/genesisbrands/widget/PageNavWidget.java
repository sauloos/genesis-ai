package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetDescriptor;
import ai.genesisbrands.platform.WidgetOutcome;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Back/Next navigation, driven entirely by the page's own outcomes — Back (if the page
 * has a "back" transition) left-aligned, every other outcome (typically "next")
 * right-aligned. Pages render no navigation at all unless this widget is placed on
 * them; navigation is opt-in per page, not implied by layout.
 */
@Component
public class PageNavWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "pageNav";
    }

    @Override
    public String displayName() {
        return "Page navigation";
    }

    @Override
    public String description() {
        return "Back (left) / Next (right) buttons for the page's own outcomes. Add this widget to any page that should let the visitor navigate.";
    }

    @Override
    public List<WidgetOutcome> outcomes() {
        return List.of(new WidgetOutcome("back", "Back"), WidgetOutcome.DEFAULT);
    }
}
