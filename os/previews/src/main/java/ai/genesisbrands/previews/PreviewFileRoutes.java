package ai.genesisbrands.previews;

import ai.genesisbrands.security.ApiKeyExemption;
import org.springframework.stereotype.Component;

/**
 * Page images load through a plain {@code <img>} or a fetch of a signed link; the link and the
 * client's own session are the check, so the shared X-Api-Key (which ships in public JS) is not.
 * The manifest and list calls still need the key like any other client API.
 */
@Component
public class PreviewFileRoutes implements ApiKeyExemption {

    @Override
    public boolean exempts(String path) {
        return path != null && path.startsWith("/api/client/previews/p/");
    }
}
