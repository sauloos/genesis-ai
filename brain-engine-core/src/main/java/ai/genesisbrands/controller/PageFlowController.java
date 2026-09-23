package ai.genesisbrands.controller;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.PageFlowService;
import ai.genesisbrands.service.PageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admin/page-flows")
@RequiredArgsConstructor
public class PageFlowController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final PageFlowService pageFlowService;
    private final PageService pageService;

    @GetMapping
    public ResponseEntity<List<PageFlow>> list(HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(pageFlowService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            PageFlow flow = pageFlowService.get(id);
            List<Page> pages = pageService.listByFlow(id);
            return ResponseEntity.ok(new PageFlowDetail(flow, pages));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<PageFlow> create(@RequestBody CreatePageFlowRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(pageFlowService.create(body.name(), body.slug()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> rename(@PathVariable String id, @RequestBody CreatePageFlowRequest body, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(pageFlowService.rename(id, body.name(), body.slug()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping("/{id}/live")
    public ResponseEntity<?> setLive(@PathVariable String id, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(pageFlowService.setLive(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            pageFlowService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    public record CreatePageFlowRequest(String name, String slug) {}

    public record PageFlowDetail(PageFlow flow, List<Page> pages) {}

    public record ErrorResponse(String message) {}
}
