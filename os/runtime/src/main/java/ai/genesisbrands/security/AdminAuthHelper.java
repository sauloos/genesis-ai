package ai.genesisbrands.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Shared admin Basic Auth check — used by both BasicAuthFilter and ClientAuthFilter
 * so the validation logic lives in one place.
 */
@Component
public class AdminAuthHelper {

    @Value("${genesis.basic-auth.username}")
    private String username;

    @Value("${genesis.basic-auth.password}")
    private String password;

    public boolean isAdminRequest(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) return false;
            return username.equals(decoded.substring(0, sep)) && password.equals(decoded.substring(sep + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
