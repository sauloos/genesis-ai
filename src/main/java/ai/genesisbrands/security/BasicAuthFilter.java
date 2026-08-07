package ai.genesisbrands.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * Minimal HTTP Basic Auth gate for the not-yet-public app UI (Consultant, Training,
 * Playground). The landing page at "/" stays open — this only protects the pages
 * listed below. Deliberately lightweight (no Spring Security dependency) since this
 * is a temporary measure until real auth ships.
 */
@Component
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
        "/console", "/console.html",
        "/training", "/training.html",
        "/playground", "/playground.html",
        "/questionnaires", "/questionnaires.html",
        "/questionnaire-run", "/questionnaire-run.html",
        "/discover"
    );

    @Value("${genesis.basic-auth.username}")
    private String username;

    @Value("${genesis.basic-auth.password}")
    private String password;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI();

        if (!PROTECTED_PATHS.contains(path) || isAuthorized(req)) {
            chain.doFilter(req, res);
            return;
        }

        res.setHeader("WWW-Authenticate", "Basic realm=\"Genesis AI\"");
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean isAuthorized(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) return false;
            String user = decoded.substring(0, sep);
            String pass = decoded.substring(sep + 1);
            return username.equals(user) && password.equals(pass);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
