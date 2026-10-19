package ai.genesisbrands.controller;

import ai.genesisbrands.platform.WidgetExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Publishes the auto-collected {@link WidgetExtension} beans to the client loader as
 * {@code {widgetType: [moduleUrl, …]}}, innermost first. Served next to the widget modules
 * themselves (outside /api) because it's as public as they are: it only lists static module URLs.
 */
@RestController
public class WidgetExtensionController {

    private final Map<String, List<String>> extensions;

    public WidgetExtensionController(ObjectProvider<WidgetExtension> beans) {
        Map<String, List<String>> byType = new TreeMap<>();
        beans.stream()
            .peek(WidgetExtensionController::check)
            .sorted(Comparator.comparingInt(WidgetExtension::order).thenComparing(WidgetExtension::module))
            .forEach(e -> byType.computeIfAbsent(e.widgetType(), k -> new ArrayList<>()).add(e.module()));
        this.extensions = byType;
    }

    @GetMapping("/widgets/extensions.json")
    public Map<String, List<String>> extensions() {
        return extensions;
    }

    private static void check(WidgetExtension e) {
        if (e.widgetType() == null || e.widgetType().isBlank()) {
            throw new IllegalStateException(e.getClass().getName() + ": widgetType is required");
        }
        if (e.module() == null || !e.module().startsWith("/") || !e.module().endsWith(".js")) {
            throw new IllegalStateException(e.getClass().getName() + ": module must be an absolute .js path, was " + e.module());
        }
    }
}
