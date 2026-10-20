package ai.genesisbrands.gardener;

import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Admin API for the knowledge gardener: its switch, its findings and a manual "Review now". */
@RestController
@RequestMapping("/api/admin/knowledge/gardener")
@RequiredArgsConstructor
@Tag(name = "Knowledge gardener (admin)")
public class GardenerAdminController {

    private final GardenerService gardener;
    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    public record ReviewRequest(String eventId, String sourceId) {}

    public record Resolution(String status, String note) {}

    @GetMapping
    @Operation(summary = "Whether the gardener is on, the module count, open findings and recent Layer 1 ingestions")
    public GardenerService.Status status(HttpServletRequest req) {
        requireAdmin(req);
        return gardener.status();
    }

    @PutMapping("/settings")
    public GardenerSettings settings(@RequestBody GardenerService.SettingsUpdate body, HttpServletRequest req) {
        requireAdmin(req);
        return gardener.updateSettings(body);
    }

    @GetMapping("/findings")
    public List<GardenerFinding> findings(@RequestParam(defaultValue = "OPEN") String status,
                                          @RequestParam(defaultValue = "200") int limit, HttpServletRequest req) {
        requireAdmin(req);
        return gardener.findings(status, limit);
    }

    @PutMapping("/findings/{id}")
    @Operation(summary = "Mark a finding APPLIED (the module was edited), DISMISSED, or OPEN again")
    public GardenerFinding resolve(@PathVariable String id, @RequestBody Resolution body, HttpServletRequest req) {
        requireAdmin(req);
        return gardener.resolve(id, body.status(), body.note());
    }

    @GetMapping("/reviews")
    public List<GardenerReview> reviews(@RequestParam(defaultValue = "50") int limit, HttpServletRequest req) {
        requireAdmin(req);
        return gardener.reviews(limit);
    }

    @PostMapping("/review")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Review an ingestion event's sources, or one source, now; works while the gardener is off")
    public Map<String, String> review(@RequestBody ReviewRequest body, HttpServletRequest req) {
        requireAdmin(req);
        return gardener.reviewNow(body.eventId(), body.sourceId());
    }

    private void requireAdmin(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "Request refused" : e.getReason()));
    }
}
