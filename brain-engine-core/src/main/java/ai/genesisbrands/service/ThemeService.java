package ai.genesisbrands.service;

import ai.genesisbrands.model.Theme;
import ai.genesisbrands.repository.ThemeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class ThemeService {

    private static final Logger log = LoggerFactory.getLogger(ThemeService.class);

    private final ThemeRepository themeRepository;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void autoImportBuiltInThemes() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] manifests = resolver.getResources("classpath*:/themes/*/theme.json");
            for (Resource manifest : manifests) {
                try {
                    importBuiltIn(manifest);
                } catch (Exception e) {
                    log.warn("Failed to import built-in theme from {}: {}", manifest.getURL(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan built-in themes: {}", e.getMessage());
        }

        // If nothing is active yet, activate genesis-brands (or first available)
        if (themeRepository.findByActiveTrue().isEmpty()) {
            themeRepository.findById("genesis-brands")
                .or(() -> themeRepository.findAll().stream().findFirst())
                .ifPresent(t -> {
                    t.setActive(true);
                    themeRepository.save(t);
                    log.info("Auto-activated theme: {}", t.getId());
                });
        }
    }

    private void importBuiltIn(Resource manifest) throws IOException {
        String manifestPath = manifest.getURL().toString();
        String themeDirPrefix = manifestPath.substring(0, manifestPath.lastIndexOf('/') + 1);

        JsonNode meta = objectMapper.readTree(manifest.getInputStream());
        String id = meta.get("id").asText();

        // Already imported — skip, but preserve active state
        if (themeRepository.existsById(id)) return;

        String cssPath = themeDirPrefix + "theme.css";
        Resource cssResource = new PathMatchingResourcePatternResolver().getResource(cssPath);
        String css = cssResource.exists()
            ? new String(cssResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8) : "";

        Theme theme = new Theme();
        theme.setId(id);
        theme.setName(meta.get("name").asText());
        theme.setVersion(meta.get("version").asText());
        theme.setDescription(meta.has("description") ? meta.get("description").asText() : "");
        theme.setBuiltIn(true);
        theme.setCssContent(css);
        themeRepository.save(theme);
        log.info("Imported built-in theme: {}", id);
    }

    public List<Theme> list() {
        return themeRepository.findAll();
    }

    public String getActiveCss() {
        return themeRepository.findByActiveTrue()
            .map(Theme::getCssContent)
            .orElse("");
    }

    @Transactional
    public Theme activate(String id) {
        Theme theme = themeRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Theme not found: " + id));
        themeRepository.deactivateAll();
        theme.setActive(true);
        return themeRepository.save(theme);
    }

    @Transactional
    public Theme importZip(MultipartFile file) throws IOException {
        String themeId = null;
        String themeName = null;
        String themeVersion = null;
        String themeDescription = "";
        String themeCss = "";

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("theme.json")) {
                    JsonNode meta = objectMapper.readTree(zis.readAllBytes());
                    themeId = meta.get("id").asText();
                    themeName = meta.get("name").asText();
                    themeVersion = meta.get("version").asText();
                    themeDescription = meta.has("description") ? meta.get("description").asText() : "";
                } else if (name.endsWith("theme.css")) {
                    themeCss = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
                zis.closeEntry();
            }
        }

        if (themeId == null) throw new IllegalArgumentException("Invalid theme zip: missing theme.json");

        Theme theme = themeRepository.findById(themeId).orElse(new Theme());
        theme.setId(themeId);
        theme.setName(themeName);
        theme.setVersion(themeVersion);
        theme.setDescription(themeDescription);
        theme.setCssContent(themeCss);
        theme.setBuiltIn(false);
        return themeRepository.save(theme);
    }

    @Transactional
    public void delete(String id) {
        Theme theme = themeRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Theme not found: " + id));
        if (theme.isBuiltIn()) throw new IllegalStateException("Cannot delete a built-in theme");
        if (theme.isActive()) throw new IllegalStateException("Cannot delete the active theme");
        themeRepository.delete(theme);
    }
}
