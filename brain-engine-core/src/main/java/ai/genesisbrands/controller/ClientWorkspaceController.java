package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.security.ClientAuthHelper;
import ai.genesisbrands.service.ClientWorkItem;
import ai.genesisbrands.service.ClientWorkspaceProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;

/**
 * Generic REST facade over whatever {@link ClientWorkspaceProvider} the tenant registers —
 * backs the core {@code dashboardAssets}/{@code dashboardAgents} widgets. Instantiated only
 * via the {@code clientWorkspaceController} @Bean in
 * {@link ai.genesisbrands.config.ClientWorkspaceConfiguration}, never by component scan —
 * see {@link ai.genesisbrands.controller.ConsultantController}'s doc comment for why this
 * split is load-bearing (packaged-jar scan-order sensitivity of @ConditionalOnBean on a
 * scanned class) and both {@code GenesisAiApplication} and {@code GenesisOsApplication}
 * exclude this class from scanning for the same reason.
 */
@RestController
@RequestMapping("/api/client/workspace")
@RequiredArgsConstructor
@Tag(name = "Client Workspace", description = "Generic client deliverables listing, backing the dashboard widgets")
public class ClientWorkspaceController {

    private final ClientWorkspaceProvider provider;
    private final ClientAuthHelper clientAuthHelper;

    @GetMapping
    @Operation(summary = "List the signed-in client's work items")
    public List<ClientWorkItem> mine(HttpServletRequest req) {
        return provider.listMine(requireClientUserId(req));
    }

    @GetMapping("/{workItemId}/variants/{variantKey}/versions/{versionNumber}")
    @Operation(summary = "Load an archived snapshot of one variant")
    public ClientWorkItem.Variant version(
        @PathVariable String workItemId,
        @PathVariable String variantKey,
        @PathVariable int versionNumber,
        HttpServletRequest req
    ) {
        return provider.loadVersion(workItemId, variantKey, versionNumber, requireClientUserId(req))
            .orElseThrow(() -> new NoSuchElementException("Version not found: " + versionNumber));
    }

    private String requireClientUserId(HttpServletRequest req) {
        return clientAuthHelper.resolve(req).map(ClientUser::getId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));
    }
}
