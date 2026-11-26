package ai.genesisbrands.security;

import org.springframework.stereotype.Component;

/**
 * Engagement routes the browser loads itself, so they can't send X-Api-Key:
 * <ul>
 *   <li>direction preview images ({@code /preview/}) load as plain {@code <img>} tags;</li>
 *   <li>brand book PDFs ({@code /pdf/}) and logo ZIPs ({@code /logos/}) are downloaded by
 *       navigating to them, so the browser streams the file with its own progress and saves
 *       it. Fetching them in script and saving a blob afterwards loses the click that allows
 *       the save once the transfer takes more than a few seconds.</li>
 * </ul>
 * Each route enforces its own check: the signed-in client must own the engagement (and, for
 * downloads, have paid), or the caller must be an admin. The shared key ships in public JS,
 * so exempting it removes no protection.
 */
@Component
public class EngagementPreviewExemption implements ApiKeyExemption {

    @Override
    public boolean exempts(String path) {
        return path.startsWith("/api/engagements/")
            && (path.contains("/preview/") || path.contains("/pdf/") || path.contains("/logos/"));
    }
}
