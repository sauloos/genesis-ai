package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * View-only document previews for the signed-in visitor. Lists the documents the visitor may
 * preview ({@code GET /api/client/previews}) and opens each in a page viewer fed by watermarked,
 * server-rasterised pages. The viewer module ({@code viewer.js}) is also what other widgets, such
 * as the variant chooser, call to "look inside" without mounting this widget.
 */
@Component
public class DocumentPreviewWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "documentPreview";
    }

    @Override
    public String displayName() {
        return "Document preview";
    }

    @Override
    public String description() {
        return "View-only, watermarked page previews of the visitor's documents, with a note on every page that is not shown.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("heading", "Heading", OptionType.STRING, List.of(), "Look inside your documents"),
            new WidgetConfigOption("intro", "Introduction", OptionType.STRING, List.of(),
                "A guided look at each document. Pages that are not part of the preview are described, not shown.")
        );
    }
}
