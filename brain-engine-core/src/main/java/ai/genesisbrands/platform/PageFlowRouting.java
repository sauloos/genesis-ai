package ai.genesisbrands.platform;

import java.util.Set;

/**
 * Pure resolution logic for a PageFlow's live URL: a root prefix ("live" by default,
 * "" when explicitly root-mounted, or any other literal segment) plus the flow's slug.
 * Not persisted itself — PageFlow.rootPrefix stores only the explicit override (null
 * means "use the default"), same computed-on-read spirit as Page.effectivePreviousPageId.
 */
public final class PageFlowRouting {

    public static final String DEFAULT_ROOT_PREFIX = "live";

    /** First-path-segment reserved list — mirrors BasicAuthFilter.PROTECTED_PATHS plus
     *  api/assets/login/flow. "live" is deliberately NOT reserved: setting it explicitly
     *  is just spelling out the default. "flow" is reserved as a permanent legacy-redirect prefix. */
    public static final Set<String> RESERVED_SEGMENTS = Set.of(
        "api", "dashboard", "console", "training", "playground", "questionnaires", "templates",
        "knowledge", "themes", "agents", "page-flows", "questionnaire-run", "discover", "login",
        "register", "assets", "flow"
    );

    private PageFlowRouting() {}

    /** Trims, lowercases, and strips leading/trailing slashes from a raw segment. */
    public static String normalizeSegment(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim().toLowerCase();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    /** The resolved path key with no leading slash: "" for root, "hello", or "live/hello". */
    public static String routeKey(String rootPrefix, String slug) {
        String prefix = rootPrefix == null ? DEFAULT_ROOT_PREFIX : normalizeSegment(rootPrefix);
        String normalizedSlug = normalizeSegment(slug);
        if (prefix.isEmpty()) {
            return normalizedSlug;
        }
        return normalizedSlug.isEmpty() ? prefix : prefix + "/" + normalizedSlug;
    }

    /** The resolved absolute path: "/" + routeKey, or "/" alone when routeKey is empty. */
    public static String fullPath(String rootPrefix, String slug) {
        String key = routeKey(rootPrefix, slug);
        return key.isEmpty() ? "/" : "/" + key;
    }

    /**
     * Rejects an explicit rootPrefix that collides with a reserved segment, or — when
     * root-mounted (empty prefix) — a slug that collides with one, since the slug then
     * becomes the URL's first path segment.
     */
    public static void validate(String rootPrefix, String slug) {
        if (rootPrefix != null) {
            String normalized = normalizeSegment(rootPrefix);
            if (RESERVED_SEGMENTS.contains(normalized)) {
                throw new IllegalArgumentException("Root prefix collides with a reserved route: " + normalized);
            }
            if (normalized.isEmpty()) {
                String normalizedSlug = normalizeSegment(slug);
                if (RESERVED_SEGMENTS.contains(normalizedSlug)) {
                    throw new IllegalArgumentException("Slug collides with a reserved route when root-mounted: " + normalizedSlug);
                }
            }
        }
    }
}
