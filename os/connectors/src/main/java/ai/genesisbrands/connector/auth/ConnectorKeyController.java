package ai.genesisbrands.connector.auth;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.security.ClientAuthHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Issuing and revoking connector keys. Admins manage every key (and are the only ones who can
 * issue PLATFORM keys); a signed-in client manages only their own CLIENT keys. The plaintext
 * key is in the issue response and nowhere else.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Connector Keys", description = "Credentials for the MCP + REST connectors")
public class ConnectorKeyController {

    private final ConnectorKeyService keyService;
    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final ClientAuthHelper clientAuth;

    // ── Admin ────────────────────────────────────────────────────────────────

    @GetMapping("/api/admin/connector-keys")
    @Operation(summary = "List every connector key (hashes never included)")
    public List<ConnectorKey> listAll(HttpServletRequest req) {
        requireAdmin(req);
        return keyService.listAll();
    }

    @PostMapping("/api/admin/connector-keys")
    @Operation(summary = "Issue a CLIENT key (for a client user) or a PLATFORM key — the key is returned once")
    public ConnectorKeyService.IssuedKey issue(@RequestBody AdminIssueRequest body, HttpServletRequest req) {
        requireAdmin(req);
        String scope = body.scope() == null ? ConnectorKey.SCOPE_CLIENT : body.scope().strip().toUpperCase();
        return keyService.issue(scope, body.clientUserId(), body.label());
    }

    @DeleteMapping("/api/admin/connector-keys/{keyId}")
    @Operation(summary = "Revoke any connector key")
    public ConnectorKey revoke(@PathVariable String keyId, HttpServletRequest req) {
        requireAdmin(req);
        return keyService.revoke(keyId);
    }

    // ── Client (own keys only) ───────────────────────────────────────────────

    @GetMapping("/api/client/connector-keys")
    @Operation(summary = "List your own connector keys")
    public List<ConnectorKey> listOwn(HttpServletRequest req) {
        return keyService.listForClient(requireClient(req).getId());
    }

    @PostMapping("/api/client/connector-keys")
    @Operation(summary = "Issue a connector key for your own account — the key is returned once")
    public ConnectorKeyService.IssuedKey issueOwn(@RequestBody(required = false) ClientIssueRequest body, HttpServletRequest req) {
        ClientUser client = requireClient(req);
        return keyService.issue(ConnectorKey.SCOPE_CLIENT, client.getId(), body == null ? null : body.label());
    }

    @DeleteMapping("/api/client/connector-keys/{keyId}")
    @Operation(summary = "Revoke one of your own connector keys")
    public ConnectorKey revokeOwn(@PathVariable String keyId, HttpServletRequest req) {
        return keyService.revokeOwn(keyId, requireClient(req).getId());
    }

    private void requireAdmin(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin credentials required");
        }
    }

    private ClientUser requireClient(HttpServletRequest req) {
        return clientAuth.resolve(req)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));
    }

    public record AdminIssueRequest(String scope, String clientUserId, String label) {}
    public record ClientIssueRequest(String label) {}
}
