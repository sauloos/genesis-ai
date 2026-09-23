package ai.genesisbrands.controller;

import ai.genesisbrands.platform.CoreAgent;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Discovery endpoint for Playground: lists whatever CoreAgent beans the running tenant
 * has registered. Empty on a bare platform instance. See AdminNavExtension for the
 * equivalent pattern applied to admin nav items.
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentRegistryController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final List<CoreAgent> agents;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Map<String, Object>> result = agents.stream()
                .map(agent -> Map.<String, Object>of(
                        "agentId", agent.agentId(),
                        "displayName", agent.displayName(),
                        "description", agent.description(),
                        "testEndpoint", agent.testEndpoint()))
                .toList();
        return ResponseEntity.ok(result);
    }
}
