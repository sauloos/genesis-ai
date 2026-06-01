package com.genesisai.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads Layer 1b reasoning modules from YAML files at startup.
 * These are injected into every Genesis AI prompt as methodology context.
 */
@Service
public class Layer1Service {

    private static final Path MODULES_DIR = Paths.get("knowledge/layer1/modules");
    private final Map<String, String> modules = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        if (!Files.exists(MODULES_DIR)) {
            return;
        }
        try (var walk = Files.walk(MODULES_DIR)) {
            walk.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .sorted()
                .forEach(this::loadModule);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Layer 1 modules", e);
        }
    }

    private void loadModule(Path path) {
        try {
            String key = MODULES_DIR.relativize(path).toString();
            String content = Files.readString(path);
            modules.put(key, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read module: " + path, e);
        }
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
            sb.append("## ").append(key).append("\n\n");
            sb.append(content).append("\n\n---\n\n");
        });
        return sb.toString();
    }
}
