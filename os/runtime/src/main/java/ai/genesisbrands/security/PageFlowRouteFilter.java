package ai.genesisbrands.security;

import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.platform.PageFlowRouting;
import ai.genesisbrands.repository.PageFlowRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Resolves any GET request path against the set of currently-live PageFlows'
 * configured routes (PageFlow.rootPrefix + slug, see PageFlowRouting) and forwards a
 * match to flow-runtime.html?slug=... A miss falls through to normal Spring MVC /
 * static-resource handling — no @GetMapping("/**") controller route is used here since
 * that would sit ahead of Boot's static-resource handler in RequestMappingHandlerMapping
 * priority and swallow every CSS/JS/image request (see WebMvcConfig's own comment
 * against a resource-handler "/**" mapping).
 */
@Component
@RequiredArgsConstructor
public class PageFlowRouteFilter extends OncePerRequestFilter {

    private final PageFlowRepository pageFlowRepo;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI();

        // Perf short-circuit, not a security boundary: a false negative here just falls
        // through to normal routing/404, same as a route-key miss below.
        if (!"GET".equalsIgnoreCase(req.getMethod()) || path.startsWith("/api/") || path.contains(".")) {
            chain.doFilter(req, res);
            return;
        }

        String requestKey = (path.equals("/") ? "" : path.substring(1)).toLowerCase();
        String firstSegment = requestKey.contains("/") ? requestKey.substring(0, requestKey.indexOf('/')) : requestKey;
        if (!firstSegment.isEmpty() && PageFlowRouting.RESERVED_SEGMENTS.contains(firstSegment)) {
            chain.doFilter(req, res);
            return;
        }

        for (PageFlow flow : pageFlowRepo.findAllByLiveTrue()) {
            if (PageFlowRouting.routeKey(flow.getRootPrefix(), flow.getSlug()).equals(requestKey)) {
                String slug = URLEncoder.encode(flow.getSlug(), StandardCharsets.UTF_8);
                // rootPrefix disambiguates a slug shared by two live flows under different
                // prefixes (see PageFlowRepository#findLiveByRoute) — only appended when
                // explicitly set, so a null (default "/live") prefix stays omitted, matching
                // the request-param absent/empty distinction the client reads it back with.
                String forwardUrl = "/flow-runtime.html?slug=" + slug;
                if (flow.getRootPrefix() != null) {
                    forwardUrl += "&rootPrefix=" + URLEncoder.encode(flow.getRootPrefix(), StandardCharsets.UTF_8);
                }
                req.getRequestDispatcher(forwardUrl).forward(req, res);
                return;
            }
        }

        chain.doFilter(req, res);
    }
}
