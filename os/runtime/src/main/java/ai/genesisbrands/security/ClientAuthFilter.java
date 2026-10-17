package ai.genesisbrands.security;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.platform.PageFlowRouting;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.repository.PageRepository;
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
 * Guards /your-brand/** (always) and /live/** (unless the requested route resolves to a
 * live PageFlow whose start page has requiresAuth=false, e.g. a public questionnaire
 * entry point) — requires either admin Basic Auth or a valid client session cookie
 * (_gst). Public pages (/login, /register) pass through without auth.
 *
 * /live/** route resolution deliberately mirrors PageFlowRouteFilter's — a page's own
 * requiresAuth flag is the source of truth for whether it's public, not the URL prefix,
 * since a flow can legitimately mix public and auth-gated pages (e.g. Brand Journey's
 * public Questionnaire/Sign-in pages followed by auth-gated brand-direction pages).
 */
@Component
@RequiredArgsConstructor
public class ClientAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE = "_gst";

    private final AdminAuthHelper adminAuth;
    private final ClientAuthService clientAuthService;
    private final PageFlowRepository pageFlowRepo;
    private final PageRepository pageRepo;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI();

        boolean isYourBrand = path.startsWith("/your-brand");
        boolean isLive = path.startsWith("/live");
        if (!isYourBrand && !isLive) {
            chain.doFilter(req, res);
            return;
        }

        if (isLive && "GET".equalsIgnoreCase(req.getMethod()) && !requiresAuthForLiveRoute(path)) {
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

    /**
     * Resolves a /live/<slug> path to its live PageFlow and checks the start page's
     * requiresAuth flag. Any resolution miss (unknown route, no start page, flow/page
     * data inconsistency) defaults to true — auth required — same as today's blanket
     * behavior, so this only ever narrows the gate, never widens it beyond what a
     * page's own config says.
     */
    private boolean requiresAuthForLiveRoute(String path) {
        String requestKey = (path.equals("/") ? "" : path.substring(1)).toLowerCase();
        for (PageFlow flow : pageFlowRepo.findAllByLiveTrue()) {
            if (!PageFlowRouting.routeKey(flow.getRootPrefix(), flow.getSlug()).equals(requestKey)) {
                continue;
            }
            if (flow.getStartPageId() == null) {
                return true;
            }
            return pageRepo.findById(flow.getStartPageId())
                .map(Page::isRequiresAuth)
                .orElse(true);
        }
        return true;
    }

    public static String extractSessionCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (SESSION_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
