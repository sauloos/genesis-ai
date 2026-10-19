package ai.genesisbrands.security;

import ai.genesisbrands.platform.AdminNavExtension;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
        "/dashboard", "/dashboard.html",
        "/dashboard/console", "/console", "/console.html",
        "/dashboard/training", "/training", "/training.html",
        "/dashboard/playground", "/playground", "/playground.html",
        "/dashboard/questionnaires", "/questionnaires", "/questionnaires.html",
        "/dashboard/products", "/products", "/products.html",
        "/dashboard/templates", "/templates", "/templates.html",
        "/dashboard/knowledge", "/knowledge", "/knowledge.html",
        "/dashboard/themes", "/themes", "/themes.html",
        "/dashboard/agents", "/agents", "/agents.html",
        "/dashboard/page-flows", "/page-flows", "/page-flows.html",
        "/dashboard/widget-preview", "/widget-preview.html",
        "/dashboard/usage", "/usage.html"
    );

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;
    private final Set<String> protectedPaths;

    /**
     * Every admin page a module contributes through {@link AdminNavExtension} is protected too:
     * its {@code /dashboard/<name>} path and the {@code /<name>.html} it forwards to, so a
     * module's admin page needs no edit here.
     */
    public BasicAuthFilter(AdminAuthHelper adminAuth, AdminSessionService adminSession, List<AdminNavExtension> navExtensions) {
        this.adminAuth = adminAuth;
        this.adminSession = adminSession;
        Set<String> paths = new HashSet<>(PROTECTED_PATHS);
        for (AdminNavExtension ext : navExtensions) {
            String path = ext.path();
            if (path == null || !path.startsWith("/dashboard/")) continue;
            paths.add(path);
            paths.add("/" + path.substring("/dashboard/".length()) + ".html");
        }
        this.protectedPaths = Set.copyOf(paths);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI();

        if (!protectedPaths.contains(path)) {
            chain.doFilter(req, res);
            return;
        }

        // Accept a valid session cookie (set on a previous successful Basic Auth)
        if (adminSession.hasValidSession(req)) {
            chain.doFilter(req, res);
            return;
        }

        // Accept Basic Auth credentials and issue a session cookie for future requests
        if (adminAuth.isAdminRequest(req)) {
            adminSession.setSessionCookie(res);
            chain.doFilter(req, res);
            return;
        }

        res.setHeader("WWW-Authenticate", "Basic realm=\"Genesis AI\"");
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
