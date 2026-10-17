package ai.genesisbrands.controller;

import ai.genesisbrands.model.PageTransition;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.PageTransitionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/pages/{pageId}/transitions")
@RequiredArgsConstructor
public class PageTransitionController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final PageTransitionService pageTransitionService;

    @PutMapping
    public ResponseEntity<?> upsert(@PathVariable String pageId, @RequestBody UpsertTransitionRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            if (body.targetKind() == null) {
                pageTransitionService.clearTransition(pageId, body.outcomeKey());
                return ResponseEntity.noContent().build();
            }
            PageTransition transition = pageTransitionService.setTransition(
                pageId, body.outcomeKey(), body.targetKind(), body.targetPageId());
            return ResponseEntity.ok(transition);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    public record UpsertTransitionRequest(String outcomeKey, String targetKind, String targetPageId) {}
    public record ErrorResponse(String message) {}
}
