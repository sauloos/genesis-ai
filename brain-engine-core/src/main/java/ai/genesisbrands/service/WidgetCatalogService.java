package ai.genesisbrands.service;

import ai.genesisbrands.platform.PageLayout;
import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetDescriptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WidgetCatalogService {

    private final List<WidgetDescriptor> widgetDescriptors;

    public List<WidgetTypeEntry> listWidgetTypes() {
        return widgetDescriptors.stream()
            .map(d -> new WidgetTypeEntry(d.widgetType(), d.displayName(), d.description(), d.configOptions()))
            .toList();
    }

    public List<PageLayout> listLayouts() {
        return PageLayout.ALL;
    }

    public record WidgetTypeEntry(
        String widgetType,
        String displayName,
        String description,
        List<WidgetConfigOption> configOptions
    ) {}
}
