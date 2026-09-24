package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * First tenant-specific widget (genesis-brands, not core): triggers the
 * questionnaire-answers-to-three-directions pipeline from context, polls it, and lets
 * the (always-authenticated, per requiresAuth on its page) visitor pick a direction
 * from watermarked previews. Pairs with BrandResultsController's start endpoint.
 */
@Component
public class BrandResultsWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "brandResults";
    }

    @Override
    public String displayName() {
        return "Brand results";
    }

    @Override
    public String description() {
        return "Runs the brand direction pipeline and lets the visitor preview and choose a direction.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("heading", "Heading", OptionType.STRING, List.of(), "Your brand directions are ready")
        );
    }
}
