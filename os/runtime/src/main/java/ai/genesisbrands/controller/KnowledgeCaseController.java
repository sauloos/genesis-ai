package ai.genesisbrands.controller;

import ai.genesisbrands.model.KnowledgeCase;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.KnowledgeCaseService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Admin API for the case library on the Knowledge page. Admin-only: Basic Auth or the admin
 * session cookie, never the shared X-Api-Key (which ships in public JS).
 */
@RestController
@RequestMapping("/api/admin/knowledge-cases")
@RequiredArgsConstructor
public class KnowledgeCaseController {

    private final KnowledgeCaseService cases;
    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req) {
        if (!admin(req)) return forbidden();
        return ResponseEntity.ok(cases.list().stream().map(KnowledgeCaseController::view).toList());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(HttpServletRequest req,
                                    @RequestParam String kind,
                                    @RequestParam String verdict,
                                    @RequestParam(required = false) String note,
                                    @RequestParam(required = false) String sector,
                                    @RequestParam(required = false) String sourceUrl,
                                    @RequestParam(required = false) MultipartFile image) throws IOException {
        if (!admin(req)) return forbidden();
        try {
            byte[] bytes = image == null || image.isEmpty() ? null : image.getBytes();
            String type = image == null ? null : image.getContentType();
            return ResponseEntity.ok(view(cases.create(kind, verdict, note, sector, sourceUrl, bytes, type)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
                                    @RequestBody Map<String, Object> patch) {
        if (!admin(req)) return forbidden();
        try {
            return ResponseEntity.ok(view(cases.update(id, patch)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/redraft")
    public ResponseEntity<?> redraft(HttpServletRequest req, @PathVariable String id) {
        if (!admin(req)) return forbidden();
        try {
            return ResponseEntity.ok(view(cases.redraft(id)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        if (!admin(req)) return forbidden();
        cases.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(HttpServletRequest req, @PathVariable String id) throws IOException {
        if (!admin(req)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        KnowledgeCase c = cases.get(id).orElse(null);
        if (c == null || c.getImagePath() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(c.getImageType()))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(cases.image(c));
    }

    static Map<String, Object> view(KnowledgeCase c) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", c.getId());
        m.put("kind", c.getKind());
        m.put("verdict", c.getVerdict());
        m.put("status", c.getStatus());
        m.put("title", c.getTitle());
        m.put("sector", c.getSector());
        m.put("tags", c.getTags() == null ? List.of() : List.of(c.getTags().split(",")));
        m.put("note", c.getNote());
        m.put("analysis", c.getAnalysis());
        m.put("lesson", c.getLesson());
        m.put("hasImage", c.getImagePath() != null);
        m.put("sourceUrl", c.getSourceUrl());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private boolean admin(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin credentials required"));
    }
}
