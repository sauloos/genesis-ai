package ai.genesisbrands.controller;

import ai.genesisbrands.platform.AgentViewExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Publishes the auto-collected {@link AgentViewExtension} beans to the client loader as
 * {@code {agentId: {view: [moduleUrl, …]}}}, innermost first. Served next to the view
 * modules themselves (outside /api) because it's as public as they are: it only lists
 * static module URLs.
 */
@RestController
public class AgentViewExtensionController {

    private static final Set<String> VIEWS = Set.of("live", "playground");

    private final Map<String, Map<String, List<String>>> extensions;

    public AgentViewExtensionController(ObjectProvider<AgentViewExtension> beans) {
        Map<String, Map<String, List<String>>> byAgent = new TreeMap<>();
        beans.stream()
            .peek(AgentViewExtensionController::check)
            .sorted(Comparator.comparingInt(AgentViewExtension::order).thenComparing(AgentViewExtension::module))
            .forEach(e -> byAgent
                .computeIfAbsent(e.agentId(), k -> new LinkedHashMap<>())
                .computeIfAbsent(e.view(), k -> new ArrayList<>())
                .add(e.module()));
        this.extensions = byAgent;
    }

    @GetMapping("/agent-views/extensions.json")
    public Map<String, Map<String, List<String>>> extensions() {
        return extensions;
    }

    private static void check(AgentViewExtension e) {
        if (!VIEWS.contains(e.view())) {
            throw new IllegalStateException(e.getClass().getName() + ": view must be live or playground, was " + e.view());
        }
        if (e.module() == null || !e.module().startsWith("/") || !e.module().endsWith(".js")) {
            throw new IllegalStateException(e.getClass().getName() + ": module must be an absolute .js path, was " + e.module());
        }
    }
}
