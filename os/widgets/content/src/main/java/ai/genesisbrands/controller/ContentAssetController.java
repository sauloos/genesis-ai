package ai.genesisbrands.controller;

import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.PageWidgetService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Admin uploads for a content widget's image and HTML file modes. Each file is stored under
 * content-widgets/<widgetId>/ and served back through /api/assets/.
 */
@RestController
@RequestMapping("/api/admin/pages/{pageId}/widgets")
@RequiredArgsConstructor
public class ContentAssetController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final PageWidgetService pageWidgetService;
    private final BlobStorageService blobStorageService;

    @PostMapping(value = "/{id}/content-image", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadContentImage(@PathVariable String pageId, @PathVariable String id,
                                                 @RequestParam("image") MultipartFile image, HttpServletRequest req) throws IOException {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return upload(id, image);
    }

    @PostMapping(value = "/{id}/content-html-file", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadContentHtmlFile(@PathVariable String pageId, @PathVariable String id,
                                                     @RequestParam("file") MultipartFile file, HttpServletRequest req) throws IOException {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.endsWith(".html") || filename.endsWith(".htm"))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("File must be a .html or .htm document"));
        }
        return upload(id, file);
    }

    private ResponseEntity<?> upload(String widgetId, MultipartFile file) throws IOException {
        try {
            pageWidgetService.get(widgetId); // validate exists
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
        String blobPath = "content-widgets/" + widgetId + "/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
        blobStorageService.upload(blobPath, file.getBytes());
        return ResponseEntity.ok(Map.of("url", "/api/assets/" + blobPath));
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    public record ErrorResponse(String message) {}
}
