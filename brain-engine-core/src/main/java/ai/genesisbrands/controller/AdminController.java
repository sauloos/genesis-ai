package ai.genesisbrands.controller;

import ai.genesisbrands.platform.AdminNavExtension;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final List<AdminNavExtension> navExtensions;

    @Value("${genesis.environment:dev}")
    private String environment;

    @Value("${genesis.api-key}")
    private String apiKey;

    @GetMapping("/env")
    public ResponseEntity<Map<String, String>> env(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("env", environment.toUpperCase()));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("role", "SUPER_ADMIN"));
    }

    @GetMapping("/key")
    public ResponseEntity<Map<String, String>> key(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("key", apiKey));
    }

    @GetMapping("/nav-extensions")
    public ResponseEntity<List<Map<String, Object>>> navExtensions(HttpServletRequest req) {
        if (!adminAuth.isAdminRequest(req) && !adminSession.hasValidSession(req)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Map<String, Object>> result = navExtensions.stream()
                .sorted(Comparator.comparingInt(AdminNavExtension::order))
                .map(ext -> Map.<String, Object>of("label", ext.label(), "path", ext.path(), "description", ext.description()))
                .toList();
        return ResponseEntity.ok(result);
    }
}
