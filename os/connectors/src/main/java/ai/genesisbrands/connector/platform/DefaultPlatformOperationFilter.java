package ai.genesisbrands.connector.platform;

import ai.genesisbrands.platform.connector.PlatformOperation;
import ai.genesisbrands.platform.connector.PlatformOperationFilter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Core's baseline {@link PlatformOperationFilter}: the Platform connector is admin
 * operations only, so it drops customer-facing routes (they act as a signed-in client, which
 * a platform key never is), sign-in/session routes, the connector surface itself and
 * connector-key management (a platform key must not mint more keys), and the routes that
 * would hand out the shared API key.
 */
@Component
public class DefaultPlatformOperationFilter implements PlatformOperationFilter {

    private static final List<String> EXCLUDED_PREFIXES = List.of(
        "/api/client/",
        "/api/auth/",
        "/api/public/",
        "/api/waitlist",
        "/api/connect/",
        "/api/assets/",
        "/api/themes/active/",
        "/api/agents/live-view",
        "/api/admin/key",
        "/api/admin/env",
        "/api/admin/connector-keys"
    );

    @Override
    public boolean include(PlatformOperation operation) {
        String path = operation.path();
        if (EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith)) return false;
        return !(path.endsWith("/login") || path.endsWith("/logout"));
    }
}
