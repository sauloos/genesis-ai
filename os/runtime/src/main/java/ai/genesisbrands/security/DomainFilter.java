package ai.genesisbrands.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rejects requests that don't arrive on a tenant's configured custom domain(s). Azure
 * Container Apps keeps the default *.azurecontainerapps.io hostname reachable even
 * after a custom domain is bound — there's no platform-level way to disable it — so
 * this closes that off at the app layer. Localhost is left open for local dev. An
 * empty {@code genesis.allowed-hosts} (the default) allows any host — a bare platform
 * instance with no production domain of its own doesn't need this restriction.
 */
@Component
public class DomainFilter extends OncePerRequestFilter {

    private final Set<String> allowedHosts;

    public DomainFilter(@Value("${genesis.allowed-hosts:}") String allowedHosts) {
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
            .map(String::trim)
            .filter(host -> !host.isEmpty())
            .collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {

        String host = req.getServerName();

        if (allowedHosts.isEmpty() || allowedHosts.contains(host)
            || host.equals("localhost") || host.equals("127.0.0.1")) {
            chain.doFilter(req, res);
            return;
        }

        res.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
}
