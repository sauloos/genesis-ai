package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Charges for whatever tier was last picked on a sibling ProductOptionsWidget (named via
 * sourceWidgetId), through Stripe Checkout — or, in MOCK mode (the default), simulates a
 * successful charge with no Stripe involvement at all. Carries no product config itself;
 * it always defers to the source widget's current selection at checkout time.
 */
@Component
public class PaymentWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "payment";
    }

    @Override
    public String displayName() {
        return "Payment";
    }

    @Override
    public String description() {
        return "Charges for the currently selected option on a sibling Product Options widget, via Stripe Checkout (or mock).";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("sourceWidgetId", "Product Options widget", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("heading", "Heading (optional)", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("buttonLabel", "Button label (optional)", OptionType.STRING, List.of(), "")
        );
    }
}
