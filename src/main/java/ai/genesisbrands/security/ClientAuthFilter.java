package ai.genesisbrands.security;

import ai.genesisbrands.service.ClientAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Guards /your-brand/** — requires either admin Basic Auth or a valid client session
 * cookie (_gst). Public pages (/login, /register) pass through without auth.
 */
@Component
@RequiredArgsConstructor
public class ClientAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE = "_gst";

    private final AdminAuthHelper adminAuth;
    private final ClientAuthService clientAuthService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI();

        if (!path.startsWith("/your-brand")) {
            chain.doFilter(req, res);
            return;
        }

        // Admin always passes through
        if (adminAuth.isAdminRequest(req)) {
            chain.doFilter(req, res);
            return;
        }

        // Valid client session passes through
        String token = extractSessionCookie(req);
        if (clientAuthService.validateSession(token).isPresent()) {
            chain.doFilter(req, res);
            return;
        }

        // Redirect to login, preserving destination
        String next = URLEncoder.encode(path, StandardCharsets.UTF_8);
        res.sendRedirect("/login?next=" + next);
    }

    public static String extractSessionCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (SESSION_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
