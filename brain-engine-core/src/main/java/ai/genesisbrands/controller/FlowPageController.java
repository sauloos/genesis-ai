package ai.genesisbrands.controller;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.PageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
public class FlowPageController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final PageService pageService;

    @PostMapping("/api/admin/page-flows/{flowId}/pages")
    public ResponseEntity<?> create(@PathVariable String flowId, @RequestBody CreatePageRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(pageService.create(flowId, body.name()));
    }

    @PutMapping("/api/admin/pages/{id}")
    public ResponseEntity<?> updateMetadata(@PathVariable String id, @RequestBody UpdateMetadataRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return handle(req, () -> pageService.updateMetadata(id, body.name(), body.requiresAuth(), body.errorPage(), body.endPage()));
    }

    @PutMapping("/api/admin/pages/{id}/position")
    public ResponseEntity<?> updatePosition(@PathVariable String id, @RequestBody UpdatePositionRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return handle(req, () -> pageService.updatePosition(id, body.x(), body.y()));
    }

    @PutMapping("/api/admin/pages/{id}/previous-page")
    public ResponseEntity<?> updatePreviousPage(@PathVariable String id, @RequestBody UpdatePreviousPageRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return handle(req, () -> pageService.updatePreviousPage(id, body.previousPageId()));
    }

    @PutMapping("/api/admin/pages/{id}/layout")
    public ResponseEntity<?> updateLayout(@PathVariable String id, @RequestBody UpdateLayoutRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return handle(req, () -> pageService.updateLayout(id, body.layoutKey()));
    }

    @DeleteMapping("/api/admin/pages/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            pageService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    private ResponseEntity<?> handle(HttpServletRequest req, java.util.function.Supplier<Page> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    public record CreatePageRequest(String name) {}
    public record UpdateMetadataRequest(String name, boolean requiresAuth, boolean errorPage, boolean endPage) {}
    public record UpdatePositionRequest(double x, double y) {}
    public record UpdatePreviousPageRequest(String previousPageId) {}
    public record UpdateLayoutRequest(String layoutKey) {}
    public record ErrorResponse(String message) {}
}
