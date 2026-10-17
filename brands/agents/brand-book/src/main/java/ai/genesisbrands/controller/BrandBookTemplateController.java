package ai.genesisbrands.controller;

import ai.genesisbrands.model.BrandBookTemplate;
import ai.genesisbrands.service.BrandBookTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/brand-book-templates")
@RequiredArgsConstructor
public class BrandBookTemplateController {

    private final BrandBookTemplateService templateService;

    @GetMapping
    public List<BrandBookTemplate> list() {
        return templateService.listAll();
    }

    @GetMapping("/{id}")
    public BrandBookTemplate get(@PathVariable String id) {
        return templateService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BrandBookTemplate create(@RequestBody CreateRequest req) {
        return templateService.create(
            req.name(), req.description(), req.classpathPath(),
            req.sectionManifestJson(), req.selectionHintsJson());
    }

    @PutMapping("/{id}")
    public BrandBookTemplate update(@PathVariable String id, @RequestBody UpdateRequest req) {
        return templateService.update(id, req.name(), req.description(),
            req.sectionManifestJson(), req.selectionHintsJson());
    }

    @PostMapping("/{id}/set-current")
    public BrandBookTemplate setCurrent(@PathVariable String id) {
        return templateService.setStatus(id, BrandBookTemplate.Status.CURRENT);
    }

    @PostMapping("/{id}/set-deprecated")
    public BrandBookTemplate setDeprecated(@PathVariable String id) {
        return templateService.setStatus(id, BrandBookTemplate.Status.DEPRECATED);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        templateService.delete(id);
    }

    public record CreateRequest(
        String name,
        String description,
        String classpathPath,
        String sectionManifestJson,
        String selectionHintsJson
    ) {}

    public record UpdateRequest(
        String name,
        String description,
        String sectionManifestJson,
        String selectionHintsJson
    ) {}
}
