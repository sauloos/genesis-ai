package ai.genesisbrands.connector.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who is calling a connector, resolved server-side by {@link ConnectorAuthFilter} from the
 * connector key — never from anything in the request body. For a client key,
 * {@code clientUserId} is the only identity every downstream lookup may use.
 */
public record ConnectorPrincipal(String keyId, String keyLabel, String scope, String clientUserId) {

    static final String REQUEST_ATTRIBUTE = ConnectorPrincipal.class.getName();

    public static ConnectorPrincipal from(HttpServletRequest req) {
        Object principal = req.getAttribute(REQUEST_ATTRIBUTE);
        if (principal instanceof ConnectorPrincipal p) return p;
        // Unreachable behind ConnectorAuthFilter; fail closed if a route ever escapes it.
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector key required");
    }

    public boolean isPlatform() {
        return ConnectorKey.SCOPE_PLATFORM.equals(scope);
    }
}
