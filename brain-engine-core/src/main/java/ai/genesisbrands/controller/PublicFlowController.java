package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.platform.PageLayout;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.AuthRequiredException;
import ai.genesisbrands.service.PublicFlowRuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Public, unauthenticated counterpart to FlowSessionController — drives a real
 * visitor's session through a live PageFlow. No X-Api-Key exemption is added here;
 * the client sends the same shared static key questionnaire-run.html already sends.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicFlowController {

    private final PublicFlowRuntimeService publicFlowRuntimeService;
    private final ClientAuthHelper clientAuthHelper;

    private String clientUserId(HttpServletRequest req) {
        return clientAuthHelper.resolve(req).map(ClientUser::getId).orElse(null);
    }

    @PostMapping("/flow-sessions")
    public ResponseEntity<?> start(@RequestBody StartRequest body, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(publicFlowRuntimeService.start(body.slug(), body.rootPrefix(), clientUserId(req)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/flow-sessions/{token}")
    public ResponseEntity<?> resume(@PathVariable String token, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(publicFlowRuntimeService.resume(token, clientUserId(req)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (AuthRequiredException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/flow-sessions/{token}/advance")
    public ResponseEntity<?> advance(@PathVariable String token, @RequestBody AdvanceRequest body, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(publicFlowRuntimeService.advance(token, body.outcomeKey(), clientUserId(req)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (AuthRequiredException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping("/flow-sessions/{token}/context")
    public ResponseEntity<?> updateContext(@PathVariable String token, @RequestBody ContextPatchRequest body, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(publicFlowRuntimeService.updateContext(token, body.contextPatchJson(), clientUserId(req)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/page-layouts")
    public List<PageLayout> pageLayouts() {
        return PageLayout.ALL;
    }

    public record StartRequest(String slug, String rootPrefix) {}
    public record AdvanceRequest(String outcomeKey) {}
    public record ContextPatchRequest(String contextPatchJson) {}
    public record ErrorResponse(String message) {}
}
