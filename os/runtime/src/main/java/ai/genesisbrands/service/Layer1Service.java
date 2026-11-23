package ai.genesisbrands.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads Layer 1b reasoning modules from YAML files at startup.
 *
 * <p>By default every module is injected into every Genesis AI prompt as methodology context
 * via {@link #buildContextBlock()}. A module whose YAML declares a top-level
 * {@code context: on_demand} is loaded and addressable by {@link #getModule(String)}, but is
 * left out of that always-on block — for large modules that only one agent needs, where
 * injecting them everywhere would crowd out the prompt for no benefit.
 */
@Service
public class Layer1Service {

    static final Path MODULES_DIR = Paths.get("knowledge/layer1/modules");

    /**
     * How far above the working directory to look for the modules. A deployed container runs with
     * the knowledge tree at the working directory and matches on the first try; Gradle's
     * {@code bootRun} runs in the subproject that owns the app ({@code brands/app}, {@code os/app}),
     * two levels down. Before this search, a local run silently loaded zero modules and every agent
     * that reads Layer 1 failed with "No Layer 1 module found" — far from its cause.
     */
    private static final int MAX_PARENTS = 4;

    /** Matches a top-level (unindented) {@code context:} key in a module's YAML. */
    private static final Pattern CONTEXT_KEY = Pattern.compile("^context:\\s*([A-Za-z_]+)\\s*(?:#.*)?$");

    private static final Logger log = LoggerFactory.getLogger(Layer1Service.class);

    private final Map<String, String> modules = new LinkedHashMap<>();
    private final Set<String> onDemandKeys = new LinkedHashSet<>();

    /** Where the modules were actually found, so keys stay relative to it. */
    private Path modulesDir;

    @PostConstruct
    public void load() {
        modulesDir = findModulesDir();
        if (modulesDir == null) {
            log.warn("No Layer 1 modules directory found at or above {} — every agent that reads a "
                + "module will fail. Expected {}.",
                Paths.get("").toAbsolutePath(), MODULES_DIR);
            return;
        }
        try (var walk = Files.walk(modulesDir)) {
            walk.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .sorted()
                .forEach(this::loadModule);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Layer 1 modules", e);
        }
        log.info("Loaded {} Layer 1 modules from {} ({} on demand)",
            modules.size(), modulesDir.toAbsolutePath(), onDemandKeys.size());
    }

    /**
     * The modules directory at the working directory, or in the nearest ancestor that has one.
     * Returns null when no ancestor within {@value #MAX_PARENTS} levels holds the tree.
     */
    static Path findModulesDir() {
        Path base = Paths.get("").toAbsolutePath();
        for (int i = 0; i <= MAX_PARENTS && base != null; i++, base = base.getParent()) {
            Path candidate = base.resolve(MODULES_DIR);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void loadModule(Path path) {
        try {
            String key = modulesDir.relativize(path).toString();
            String content = Files.readString(path);
            modules.put(key, content);
            if (isOnDemand(content)) {
                onDemandKeys.add(key);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read module: " + path, e);
        }
    }

    /**
     * True when the module declares a top-level {@code context: on_demand}. Anything else,
     * including no {@code context} key at all, keeps the original always-in-context behaviour.
     */
    static boolean isOnDemand(String content) {
        for (String line : content.split("\n", -1)) {
            var m = CONTEXT_KEY.matcher(line.stripTrailing());
            if (m.matches()) {
                return "on_demand".equals(m.group(1));
            }
        }
        return false;
    }

    /**
     * Returns the raw content of a single module by its path relative to
     * knowledge/layer1/modules (e.g. "evaluation/copy-rubric.yaml").
     */
    public String getModule(String key) {
        String content = modules.get(key);
        if (content == null) {
            throw new IllegalStateException("No Layer 1 module found for: " + key);
        }
        return content;
    }

    public Map<String, String> listModules() {
        return java.util.Collections.unmodifiableMap(modules);
    }

    /** Keys of the modules kept out of {@link #buildContextBlock()}. */
    public Set<String> listOnDemandModules() {
        return java.util.Collections.unmodifiableSet(onDemandKeys);
    }

    public String buildContextBlock() {
        if (modules.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("# Layer 1 — Agency Methodology\n\n");
        sb.append("The following structured modules define the agency's methodology. ");
        sb.append("Apply these as reasoning guides in every response.\n\n");
        modules.forEach((key, content) -> {
            if (onDemandKeys.contains(key)) {
                return;
            }
            sb.append("## ").append(key).append("\n\n");
            sb.append(content).append("\n\n---\n\n");
        });
        return sb.toString();
    }
}
