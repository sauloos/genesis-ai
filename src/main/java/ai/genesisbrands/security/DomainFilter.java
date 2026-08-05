package ai.genesisbrands.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Rejects requests that don't arrive on the genesisbrands.ai custom domain. Azure
 * Container Apps keeps the default *.azurecontainerapps.io hostname reachable even
 * after a custom domain is bound — there's no platform-level way to disable it — so
 * this closes that off at the app layer. Localhost is left open for local dev.
 */
@Component
public class DomainFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "genesisbrands.ai", "www.genesisbrands.ai"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String host = req.getServerName();

        if (ALLOWED_HOSTS.contains(host) || host.equals("localhost") || host.equals("127.0.0.1")) {
            chain.doFilter(req, res);
            return;
        }

        res.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
}
