package ai.genesisbrands.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Minimal HTTP Basic Auth gate for admin-only pages. The landing page at "/" and the
 * client-facing /your-brand/* pages stay open (handled by ClientAuthFilter). Deliberately
 * lightweight — temporary until real admin auth ships.
 */
@Component
@RequiredArgsConstructor
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
        "/console", "/console.html",
        "/training", "/training.html",
        "/playground", "/playground.html",
        "/questionnaires", "/questionnaires.html",
        "/questionnaire-run", "/questionnaire-run.html",
        "/discover"
    );

    private final AdminAuthHelper adminAuth;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI();

        if (!PROTECTED_PATHS.contains(path) || adminAuth.isAdminRequest(req)) {
            chain.doFilter(req, res);
            return;
        }

        res.setHeader("WWW-Authenticate", "Basic realm=\"Genesis AI\"");
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
