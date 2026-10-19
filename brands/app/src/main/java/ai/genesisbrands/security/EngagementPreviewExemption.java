package ai.genesisbrands.security;

import org.springframework.stereotype.Component;

/**
 * Direction preview images (/api/engagements/{id}/preview/{direction}) load as plain
 * {@code <img>} tags in the brandResults widget, so they can't send X-Api-Key. The route
 * enforces its own session-based ownership check.
 */
@Component
public class EngagementPreviewExemption implements ApiKeyExemption {

    @Override
    public boolean exempts(String path) {
        return path.startsWith("/api/engagements/") && path.contains("/preview/");
    }
}
