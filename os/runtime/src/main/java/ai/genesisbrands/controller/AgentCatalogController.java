package ai.genesisbrands.controller;

import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.AgentCatalogService;
import ai.genesisbrands.service.AgentCatalogService.AgentCatalogEntry;
import ai.genesisbrands.service.AgentCatalogService.UpdateAgentConfigRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Admin-only catalog + config surface over the CoreAgent registry: lists every
 * registered agent's descriptor alongside its admin-set config, and lets the admin
 * edit that config. Deliberately a separate route from GET /api/agents
 * (AgentRegistryController), which training.html's asset-type chips already depend on
 * and which must keep its existing shape untouched.
 */
@RestController
@RequestMapping("/api/admin/agents")
@RequiredArgsConstructor
public class AgentCatalogController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final AgentCatalogService catalogService;

    @GetMapping
    public ResponseEntity<List<AgentCatalogEntry>> list(HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(catalogService.listCatalog());
    }

    @PutMapping("/{agentId}/config")
    public ResponseEntity<?> updateConfig(
            @PathVariable String agentId,
            @RequestBody UpdateAgentConfigRequest body,
            HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(catalogService.updateConfig(agentId, body));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/{agentId}/config")
    public ResponseEntity<?> resetConfig(@PathVariable String agentId, HttpServletRequest req) {
        if (!authorized(req)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            catalogService.resetConfig(agentId);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    private boolean authorized(HttpServletRequest req) {
        return adminAuth.isAdminRequest(req) || adminSession.hasValidSession(req);
    }

    public record ErrorResponse(String message) {}
}
