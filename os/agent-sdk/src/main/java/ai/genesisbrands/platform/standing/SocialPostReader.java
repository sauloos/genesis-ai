package ai.genesisbrands.platform.standing;

import java.time.Instant;
import java.util.List;

/**
 * What a client recently posted on the social accounts they connected, for agents that review
 * it. An app that adopts the publishing runtime provides the one implementation; an agent asks
 * for it optionally and works without it. Read-only, and only ever for the run's own client.
 */
public interface SocialPostReader {

    /** Up to {@code perAccount} posts from each connected account, newest first per account. */
    Feed recentPosts(String clientUserId, int perAccount);

    /**
     * @param posts      what could be read
     * @param unreadable one line per connected account that could not be read, and why
     */
    record Feed(List<SocialPost> posts, List<String> unreadable) {

        public static Feed empty() {
            return new Feed(List.of(), List.of());
        }
    }

    /** @param network e.g. {@code facebook}, {@code instagram}; {@code account} is its display name */
    record SocialPost(String network, String account, String text, String imageUrl, String url, Instant postedAt) {}
}
