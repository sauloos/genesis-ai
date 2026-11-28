package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lets the signed-in visitor compare the variants of one of their work items (for Genesis
 * Brands, the three brand directions) and choose one. Reads the generic client workspace
 * ({@code GET /api/client/workspace}) and chooses through {@code POST
 * /api/client/workspace/{item}/variants/{key}/choose}, so it knows nothing about engagements.
 * Another widget may mount it with a {@code workItemId} in its config.
 */
@Component
public class VariantChooserWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "variantChooser";
    }

    @Override
    public String displayName() {
        return "Variant chooser";
    }

    @Override
    public String description() {
        return "Compare the variants of a work item side by side and choose one. Blocks the page until one is chosen.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("heading", "Heading", OptionType.STRING, List.of(), "Your brand directions are ready"),
            new WidgetConfigOption("intro", "Introduction", OptionType.STRING, List.of(),
                "Three ways to be you. Compare them, look inside, then choose the one that feels right.")
        );
    }
}
