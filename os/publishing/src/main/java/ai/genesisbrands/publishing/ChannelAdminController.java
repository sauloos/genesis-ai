package ai.genesisbrands.publishing;

import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Whether publishing is set up, which platforms are configured, and the latest posts across clients. */
@RestController
@RequestMapping("/api/admin/channels")
@RequiredArgsConstructor
@Tag(name = "Channels (admin)")
public class ChannelAdminController {

    private final PublishingService publishing;
    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    @GetMapping
    @Operation(summary = "Publishing setup status and recent posts")
    public PublishingService.AdminStatus status(@RequestParam(defaultValue = "50") int limit, HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
        return publishing.adminStatus(limit);
    }
}
