package ai.genesisbrands.controller;

import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.FlowSessionService;
import ai.genesisbrands.service.PublicFlowRuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/**
 * Admin-gated harness for exercising FlowSession traversal (used by the Simulate panel
 * in page-flows.html/playground.html, embedded as an iframe running flow-runtime.html
 * in simulate mode). Delegates into PublicFlowRuntimeService so a simulated session
 * renders through the exact same PublicSessionView shape a real visitor gets — the
 * difference is entirely in how the session starts (by pageFlowId, not a live slug)
 * and in the simulated flag it carries, not in the response contract.
 */
@RestController
@RequestMapping("/api/admin/flow-sessions")
@RequiredArgsConstructor
public class FlowSessionController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final FlowSessionService flowSessionService;
    private final PublicFlowRuntimeService publicFlowRuntimeService;

    @PostMapping
    public ResponseEntity<?> start(@RequestBody StartRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(publicFlowRuntimeService.startSimulated(body.pageFlowId()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> get(@PathVariable String token, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(publicFlowRuntimeService.resume(token, null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping("/{token}/context")
    public ResponseEntity<?> updateContext(@PathVariable String token, @RequestBody ContextPatchRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(publicFlowRuntimeService.updateContext(token, body.contextPatchJson(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{token}/advance")
    public ResponseEntity<?> advance(@PathVariable String token, @RequestBody AdvanceRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(publicFlowRuntimeService.advance(token, body.outcomeKey(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{token}/history")
    public ResponseEntity<?> history(@PathVariable String token, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(flowSessionService.history(token));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    public record StartRequest(String pageFlowId) {}
    public record ContextPatchRequest(String contextPatchJson) {}
    public record AdvanceRequest(String outcomeKey) {}
    public record ErrorResponse(String message) {}
}
