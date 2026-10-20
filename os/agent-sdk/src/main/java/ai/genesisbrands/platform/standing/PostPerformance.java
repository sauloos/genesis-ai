package ai.genesisbrands.platform.standing;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * How the posts an agent drafted did once the client published them, for agents that learn
 * what performs. The standing runtime provides the one implementation when the app adopts the
 * publishing runtime; an agent asks for it optionally and works without it. Read-only, and
 * only ever for the run's own client and the asking agent's own items.
 */
public interface PostPerformance {

    /** The agent's posts the client published since {@code since}, newest first, one entry per account. */
    List<PublishedPost> published(String clientUserId, String agentId, Instant since);

    /**
     * @param payload the inbox item's payload as the agent wrote it (e.g. {@code pillar}, {@code format})
     * @param network e.g. {@code instagram}, {@code linkedin}; {@code account} is its display name
     * @param metrics null until the platform's figures were first read
     */
    record PublishedPost(String itemId, String title, Map<String, Object> payload, String network, String account,
                         Instant publishedAt, Metrics metrics) {}

    /** Each figure null when the platform does not give it or the connection may not read it. */
    record Metrics(Long impressions, Long reach, Long reactions, Long comments, Long shares, Long saves, Long clicks,
                   Instant measuredAt) {

        /** Reactions, comments, shares, saves and clicks together; unknown figures count as none. */
        public long engagements() {
            return n(reactions) + n(comments) + n(shares) + n(saves) + n(clicks);
        }

        private static long n(Long v) {
            return v == null ? 0 : v;
        }
    }
}
