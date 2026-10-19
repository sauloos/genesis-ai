package ai.genesisbrands.connector.auth;

import ai.genesisbrands.repository.ClientUserRepository;
import ai.genesisbrands.usage.UsageScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * The only way into the connector surface ({@code /api/connect/**} and {@code /mcp/**}):
 * {@code Authorization: Bearer <connector key>}. The shared X-Api-Key and admin Basic
 * credentials are deliberately not accepted here. Platform paths take only a PLATFORM key;
 * every other connector path takes only a CLIENT key bound to a still-existing client.
 */
@Component
@RequiredArgsConstructor
public class ConnectorAuthFilter extends OncePerRequestFilter {

    private final ConnectorKeyService keyService;
    private final ClientUserRepository clientUsers;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(req.getMethod())
            || !(path.startsWith("/api/connect/") || path.equals("/mcp") || path.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7) : null;
        Optional<ConnectorKey> key = keyService.authenticate(token);
        if (key.isEmpty()) {
            res.setHeader("WWW-Authenticate", "Bearer realm=\"genesis-connectors\"");
            reject(res, HttpServletResponse.SC_UNAUTHORIZED, "A valid connector key is required (Authorization: Bearer <key>)");
            return;
        }

        ConnectorKey k = key.get();
        String path = req.getRequestURI();
        boolean platformPath = path.startsWith("/api/connect/platform") || path.startsWith("/mcp/platform");
        if (platformPath != ConnectorKey.SCOPE_PLATFORM.equals(k.getScope())) {
            reject(res, HttpServletResponse.SC_FORBIDDEN, platformPath
                ? "The Platform connector needs a platform key"
                : "This connector needs a client key");
            return;
        }
        if (!platformPath && (k.getClientUserId() == null || !clientUsers.existsById(k.getClientUserId()))) {
            res.setHeader("WWW-Authenticate", "Bearer realm=\"genesis-connectors\"");
            reject(res, HttpServletResponse.SC_UNAUTHORIZED, "This key's client account no longer exists");
            return;
        }

        req.setAttribute(ConnectorPrincipal.REQUEST_ATTRIBUTE,
            new ConnectorPrincipal(k.getId(), k.getLabel(), k.getScope(), k.getClientUserId()));
        if (k.getClientUserId() != null) req.setAttribute(UsageScope.CLIENT_USER_ATTRIBUTE, k.getClientUserId());
        chain.doFilter(req, res);
    }

    private static void reject(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
