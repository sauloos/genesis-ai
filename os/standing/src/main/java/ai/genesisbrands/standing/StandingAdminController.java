package ai.genesisbrands.standing;

import ai.genesisbrands.repository.ProductRepository;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.AgentCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Admin side of standing agents: which agents are offered to clients and on what terms
 * (defaults, locked options, frequencies, monthly caps), plus every subscription and run.
 */
@RestController
@RequestMapping("/api/admin/standing")
@RequiredArgsConstructor
@Tag(name = "Standing agents (admin)")
public class StandingAdminController {

    private final StandingService standing;
    private final AgentCatalogService agentCatalog;
    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final ProductRepository products;
    private final StandingNotifier notifier;

    public record ProductChoice(String id, String name, String type, boolean active) {}

    @GetMapping("/offers")
    public List<StandingService.AdminOffer> offers(HttpServletRequest req) {
        requireAdmin(req);
        return standing.adminOffers(agentCatalog::isAvailableForLiveView);
    }

    @PutMapping("/offers/{agentId}")
    public Map<String, String> updateOffer(@PathVariable String agentId, @RequestBody StandingService.OfferUpdate body,
                                           HttpServletRequest req) {
        requireAdmin(req);
        standing.updateOffer(agentId, body);
        return Map.of("status", "saved");
    }

    @GetMapping("/products")
    @Operation(summary = "Products an offer can require, from the product catalog")
    public List<ProductChoice> products(HttpServletRequest req) {
        requireAdmin(req);
        return products.findAllByOrderBySortOrderAsc().stream()
            .map(p -> new ProductChoice(p.getId(), p.getName(), p.getType().name(), p.isActive())).toList();
    }

    @GetMapping("/notifications")
    @Operation(summary = "Whether inbox emails are configured (spring.mail.host and genesis.standing.notify.from)")
    public Map<String, Boolean> notifications(HttpServletRequest req) {
        requireAdmin(req);
        return Map.of("emailEnabled", notifier.enabled());
    }

    @GetMapping("/subscriptions")
    public List<StandingService.AdminSubscription> subscriptions(@RequestParam(defaultValue = "200") int limit,
                                                                 HttpServletRequest req) {
        requireAdmin(req);
        return standing.adminSubscriptions(limit);
    }

    @PostMapping("/subscriptions/{id}/pause")
    public Map<String, String> pause(@PathVariable String id, HttpServletRequest req) {
        requireAdmin(req);
        standing.adminSetPaused(id, true);
        return Map.of("status", "paused");
    }

    @PostMapping("/subscriptions/{id}/resume")
    public Map<String, String> resume(@PathVariable String id, HttpServletRequest req) {
        requireAdmin(req);
        standing.adminSetPaused(id, false);
        return Map.of("status", "resumed");
    }

    @GetMapping("/runs")
    public List<StandingService.AdminRun> runs(@RequestParam(defaultValue = "30") int days,
                                               @RequestParam(defaultValue = "100") int limit,
                                               HttpServletRequest req) {
        requireAdmin(req);
        return standing.adminRuns(days, limit);
    }

    private void requireAdmin(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
    }

    /** The service's reasons are written for the person on screen; the default error body drops them. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of("message", e.getReason() == null ? "The request was refused." : e.getReason()));
    }
}
