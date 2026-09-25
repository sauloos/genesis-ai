package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders a single configured Product as a row of selectable option cards (tiers) — e.g.
 * a one-time package's Essential/Premium/Ultimate options, or a subscription's monthly
 * plans. Tier selection only; no payment processing (a separate widget handles that).
 * Generic and tenant-agnostic: which Product it renders is just a config value, authored
 * via the Products admin page.
 */
@Component
public class ProductOptionsWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "productOptions";
    }

    @Override
    public String displayName() {
        return "Product Options";
    }

    @Override
    public String description() {
        return "Selectable option cards (tiers) for a single configured product. Selection only, no payment.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("productId", "Product", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("heading", "Heading (optional)", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("advanceOnSelect", "Show a Continue button that advances the flow once an option is selected", OptionType.BOOLEAN, List.of(), "false"),
            new WidgetConfigOption("continueLabel", "Continue button label (optional)", OptionType.STRING, List.of(), "")
        );
    }
}
