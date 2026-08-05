package ai.genesisbrands.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Simple API key authentication for demo. All /api/** requests require X-Api-Key header.
 * Swagger UI and health endpoints are excluded.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    @Value("${genesis.api-key}")
    private String apiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI();

        // Only protect /api/** — everything else (UI, swagger, actuator) passes through.
        // /api/waitlist is also excluded: it's called from the public, unauthenticated
        // landing page, which has no API key to send. /api/assets/** is also excluded:
        // it's loaded via plain <img> tags (Playground, Training review), which can't
        // attach a custom header — the paths themselves are unguessable UUID/engagement-id
        // based, and the pages that reference them already sit behind BasicAuthFilter.
        if (!path.startsWith("/api/") || path.equals("/api/waitlist") || path.startsWith("/api/assets/")) {
            chain.doFilter(req, res);
            return;
        }

        String provided = req.getHeader("X-Api-Key");
        if (apiKey.equals(provided)) {
            chain.doFilter(req, res);
        } else {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Invalid or missing X-Api-Key header\"}");
        }
    }
}
