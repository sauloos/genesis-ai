package ai.genesisbrands.controller;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.PageWidgetService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admin/pages/{pageId}/widgets")
@RequiredArgsConstructor
public class PageWidgetController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final PageWidgetService pageWidgetService;

    @GetMapping
    public ResponseEntity<List<PageWidget>> list(@PathVariable String pageId, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(pageWidgetService.listByPage(pageId));
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String pageId, @RequestBody CreateWidgetRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(pageWidgetService.create(pageId, body.slotKey(), body.widgetType(), body.label(), body.configJson()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String pageId, @PathVariable String id, @RequestBody UpdateWidgetRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(pageWidgetService.update(id, body.slotKey(), body.orderInSlot(), body.label(), body.configJson()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String pageId, @PathVariable String id, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            pageWidgetService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping(value = "/{id}/content-image", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadContentImage(@PathVariable String pageId, @PathVariable String id,
                                                 @RequestParam("image") MultipartFile image, HttpServletRequest req) throws IOException {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(Map.of("url", pageWidgetService.uploadContentImage(id, image)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping(value = "/{id}/content-html-file", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadContentHtmlFile(@PathVariable String pageId, @PathVariable String id,
                                                     @RequestParam("file") MultipartFile file, HttpServletRequest req) throws IOException {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.endsWith(".html") || filename.endsWith(".htm"))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("File must be a .html or .htm document"));
        }
        try {
            return ResponseEntity.ok(Map.of("url", pageWidgetService.uploadContentHtmlFile(id, file)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    public record CreateWidgetRequest(String slotKey, String widgetType, String label, String configJson) {}
    public record UpdateWidgetRequest(String slotKey, int orderInSlot, String label, String configJson) {}
    public record ErrorResponse(String message) {}
}
