package ai.genesisbrands.connector.platform;

import ai.genesisbrands.connector.auth.ConnectorPrincipal;
import ai.genesisbrands.platform.connector.PlatformOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** REST face of the Platform connector. The MCP face ({@code /mcp/platform}) calls the same service. */
@RestController
@RequestMapping("/api/connect/platform")
@RequiredArgsConstructor
@Tag(name = "Platform Connector", description = "Admin operations — authenticate with a platform connector key")
public class PlatformConnectorController {

    private final PlatformConnectorService service;

    @GetMapping("/operations")
    @Operation(summary = "List the admin operations available, optionally filtered by a search term")
    public List<PlatformOperation> list(@RequestParam(required = false) String q) {
        return service.list(q);
    }

    @GetMapping("/operations/{operationId}")
    @Operation(summary = "Describe one admin operation: method, path and the parameters it takes")
    public PlatformOperation describe(@PathVariable String operationId) {
        return service.describe(operationId);
    }

    @PostMapping("/operations/{operationId}")
    @Operation(summary = "Run one admin operation; the result carries the operation's own HTTP status and body")
    public PlatformConnectorService.CallResult call(@PathVariable String operationId,
                                                    @RequestBody(required = false) PlatformConnectorService.CallRequest body,
                                                    HttpServletRequest req) {
        return service.call(operationId, body, ConnectorPrincipal.from(req));
    }

    @GetMapping("/audit")
    @Operation(summary = "Recent Platform connector calls, newest first")
    public List<PlatformAuditEntry> audit(@RequestParam(defaultValue = "50") int limit) {
        return service.audit(limit);
    }
}
