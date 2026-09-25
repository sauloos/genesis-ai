package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A flexible content block: heading + body text (default), a raw HTML fragment
 * composed inline with sibling widgets, an image, a full HTML document embedded via
 * a sandboxed iframe, or a centered header hero (theme logo + eyebrow label + title,
 * matching the admin dashboard's masthead) — plus optional width/height boundaries.
 * Ships alongside RedirectWidget as a second widget type so the layout editor has more
 * than one type to place, order, and configure.
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
        return "Text, raw HTML, an image, or a full HTML file, with optional sizing.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("contentMode", "Content mode", OptionType.SELECT,
                List.of("text", "html", "image", "htmlFile", "header"), "text"),
            new WidgetConfigOption("heading", "Heading", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("body", "Body", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("label", "Eyebrow label (header mode)", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("html", "HTML", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("imageUrl", "Image URL", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("imageAlt", "Image alt text", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("htmlFileUrl", "HTML file URL", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("widthMode", "Width mode", OptionType.SELECT,
                List.of("auto", "percent", "fixed"), "auto"),
            new WidgetConfigOption("width", "Width", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("heightMode", "Height mode", OptionType.SELECT,
                List.of("auto", "percent", "fixed"), "auto"),
            new WidgetConfigOption("height", "Height", OptionType.STRING, List.of(), "")
        );
    }
}
