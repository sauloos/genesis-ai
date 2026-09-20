package ai.genesisbrands.controller;

import ai.genesisbrands.model.Theme;
import ai.genesisbrands.service.ThemeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/themes")
@RequiredArgsConstructor
@Tag(name = "Themes", description = "Visual theme management — import, activate, and manage tenant themes")
public class ThemeController {

    private final ThemeService themeService;

    @GetMapping
    @Operation(summary = "List all installed themes")
    public List<Theme> list() {
        return themeService.list();
    }

    @GetMapping(value = "/active/styles.css", produces = "text/css")
    @Operation(summary = "Serve the active theme's CSS — loaded by all pages via <link>")
    public ResponseEntity<String> activeStyles() {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/css"))
            .cacheControl(CacheControl.noCache().cachePrivate())
            .body(themeService.getActiveCss());
    }

    @PutMapping("/{id}/activate")
    @Operation(summary = "Activate a theme — takes effect on next page load")
    public Theme activate(@PathVariable String id) {
        return themeService.activate(id);
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import a theme from a zip file produced by the theme module build task")
    public Theme importZip(@RequestParam("file") MultipartFile file) throws IOException {
        return themeService.importZip(file);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a non-built-in, inactive theme")
    public void delete(@PathVariable String id) {
        themeService.delete(id);
    }
}
