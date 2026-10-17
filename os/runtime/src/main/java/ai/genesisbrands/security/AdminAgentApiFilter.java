package ai.genesisbrands.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Admin-credential gate for every agent execution surface that is NOT the customer-facing
 * one — the Playground/admin facades that run agents with no live-view check and, for
 * Consultant, a caller-suppliable subject id. The shared X-Api-Key (ApiKeyFilter) is not
 * enough here: it ships in public page JS, so it identifies "our frontend", not "an admin".
 * <p>
 * Customer access to agents goes only through {@code /api/client/**} (and the engagement
 * regenerate endpoint), each of which enforces {@code AgentCatalogService
 * .requireAvailableForLiveView}. Together these implement the rule: live-available agents
 * are reachable by customers; everything else only by the app itself or with admin
 * credentials. Accepts the same credentials as BasicAuthFilter — HTTP Basic or the signed
 * admin session cookie the admin pages already carry on same-origin fetches.
 */
@Component
@RequiredArgsConstructor
public class AdminAgentApiFilter extends OncePerRequestFilter {

    private static final List<String> ADMIN_PREFIXES = List.of(
        "/api/agent-generation/",
        "/api/agent-chat/",
        "/api/consultant/",
        "/api/playground/",
        "/api/agents/"
    );

    /** Public-safe exceptions under an admin prefix — the client-facing live agent list. */
    private static final List<String> PUBLIC_PATHS = List.of("/api/agents/live-view");

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(req.getMethod()) || PUBLIC_PATHS.contains(path)) return true;
        return ADMIN_PREFIXES.stream().noneMatch(p -> path.startsWith(p) || path.equals(p.substring(0, p.length() - 1)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        if (adminSession.hasValidSession(req) || adminAuth.isAdminRequest(req)) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Admin credentials required\"}");
    }
}
