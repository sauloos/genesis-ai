package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.security.ClientAuthFilter;
import ai.genesisbrands.service.ClientAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ClientAuthController {

    private static final int COOKIE_MAX_AGE = 60 * 60 * 24 * 30; // 30 days

    private final ClientAuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest req,
                                                  HttpServletResponse res) {
        try {
            ClientUser user = authService.register(req.email(), req.name(), req.password());
            String token = authService.createSession(user.getId());
            setSessionCookie(res, token);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(user.getId(), user.getEmail(), user.getName(), null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AuthResponse(null, null, null, e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest req,
                                               HttpServletResponse res) {
        Optional<ClientUser> user = authService.login(req.email(), req.password());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthResponse(null, null, null, "Invalid email or password."));
        }
        String token = authService.createSession(user.get().getId());
        setSessionCookie(res, token);
        return ResponseEntity.ok(
            new AuthResponse(user.get().getId(), user.get().getEmail(), user.get().getName(), null)
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        String token = ClientAuthFilter.extractSessionCookie(req);
        if (token != null) authService.invalidateSession(token);
        Cookie c = new Cookie(ClientAuthFilter.SESSION_COOKIE, "");
        c.setMaxAge(0);
        c.setPath("/");
        c.setHttpOnly(true);
        res.addCookie(c);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(HttpServletRequest req) {
        String token = ClientAuthFilter.extractSessionCookie(req);
        return authService.validateSession(token)
            .map(u -> ResponseEntity.ok(new AuthResponse(u.getId(), u.getEmail(), u.getName(), null)))
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private void setSessionCookie(HttpServletResponse res, String token) {
        Cookie c = new Cookie(ClientAuthFilter.SESSION_COOKIE, token);
        c.setMaxAge(COOKIE_MAX_AGE);
        c.setPath("/");
        c.setHttpOnly(true);
        c.setSecure(true);
        res.addCookie(c);
    }

    public record AuthRequest(String email, String name, String password) {}
    public record AuthResponse(String id, String email, String name, String error) {}
}
