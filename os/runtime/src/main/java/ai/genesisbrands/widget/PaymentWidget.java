package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Charges for the visitor's whole session cart — every selection made across any number
 * of ProductOptionsWidget instances, on this page or an earlier one — through Stripe
 * Checkout, or in MOCK mode (the default) simulates a successful charge with no Stripe
 * involvement at all. Carries no product config itself; it always reads the FlowSession's
 * current cart at render and checkout time, so one payment widget on its own page can
 * settle purchases from any number of product-selection widgets earlier in the flow.
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
        return "Charges the visitor's session cart via Stripe Checkout (or mock).";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("heading", "Heading (optional)", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("buttonLabel", "Button label (optional)", OptionType.STRING, List.of(), "")
        );
    }
}
