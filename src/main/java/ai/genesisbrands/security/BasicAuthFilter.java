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

@Component
@RequiredArgsConstructor
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
        "/console", "/console.html",
        "/training", "/training.html",
        "/playground", "/playground.html",
        "/questionnaires", "/questionnaires.html",
        "/questionnaire-run", "/questionnaire-run.html",
        "/templates", "/templates.html",
        "/knowledge", "/knowledge.html",
        "/discover"
    );

    private final AdminAuthHelper adminAuth;
    private final AdminSessionService adminSession;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI();

        if (!PROTECTED_PATHS.contains(path)) {
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
