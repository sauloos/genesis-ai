package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import ai.genesisbrands.platform.WidgetOutcome;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A single, freely-placed navigation button — its own label, its own outcome (next or
 * back), its own alignment. Unlike PageNavWidget (which renders every outcome the page
 * has, in a fixed back-left/next-right layout), this is one button an author drops
 * anywhere to mean one specific thing, e.g. "Confirm selections" beneath a page of
 * Product Options widgets, right-aligned, advancing to the payment page.
 */
@Component
public class ButtonWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "button";
    }

    @Override
    public String displayName() {
        return "Button";
    }

    @Override
    public String description() {
        return "A single navigation button with its own label, direction, and alignment.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("label", "Button label", OptionType.STRING, List.of(), "Continue"),
            new WidgetConfigOption("action", "Direction", OptionType.SELECT, List.of("next", "back"), "next"),
            new WidgetConfigOption("align", "Alignment", OptionType.SELECT, List.of("left", "center", "right"), "right")
        );
    }

    @Override
    public List<WidgetOutcome> outcomes() {
        return List.of(new WidgetOutcome("back", "Back"), WidgetOutcome.DEFAULT);
    }
}
