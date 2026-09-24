package ai.genesisbrands.security;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.service.ClientAuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Shared client-session resolution — used anywhere a request needs to know which
 * (if any) ClientUser is behind it, mirroring AdminAuthHelper's role for admin auth.
 */
@Component
@RequiredArgsConstructor
public class ClientAuthHelper {

    private final ClientAuthService clientAuthService;

    public Optional<ClientUser> resolve(HttpServletRequest req) {
        String token = ClientAuthFilter.extractSessionCookie(req);
        return clientAuthService.validateSession(token);
    }
}
