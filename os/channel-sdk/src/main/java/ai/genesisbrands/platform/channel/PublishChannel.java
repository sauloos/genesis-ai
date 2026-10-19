package ai.genesisbrands.platform.channel;

import java.time.Instant;
import java.util.List;

/**
 * A social platform a client can connect and publish to. A channel module ships one bean of
 * this type and nothing else: the runtime (os/publishing) owns the OAuth round trip, token
 * storage, scheduling, retries and the public media URLs, and never knows which platform it
 * talks to. A channel never knows which agent wrote the post.
 */
public interface PublishChannel {

    /** Stable id used in URLs and stored rows, e.g. {@code meta}, {@code linkedin}. */
    String providerId();

    /** What the client sees on the connect button, e.g. "Facebook and Instagram". */
    String displayName();

    /** The networks one connection can yield. A Meta connection yields Facebook Pages and Instagram accounts. */
    List<ChannelNetwork> networks();

    /** False until the platform's app credentials are configured; the runtime then offers no connect button. */
    boolean configured();

    /** The platform's consent page. {@code state} must come back unchanged on the redirect. */
    String authorizationUrl(String state, String redirectUri);

    /** Exchanges the redirect's code for the accounts the client allowed (pages, profiles, organisations). */
    List<ChannelGrant> completeConnect(String code, String redirectUri);

    /** Publishes now. Throws {@link ChannelException} with {@code retryable} set when a later attempt may succeed. */
    PublishResult publish(ChannelAccountRef account, ChannelPost post);

    /** One network a channel publishes to and what a post there may hold. */
    record ChannelNetwork(String id, String label, int maxChars, int maxImages, boolean requiresImage) {}

    /** One account the client allowed: a page, a profile or an organisation. */
    record ChannelGrant(String network, String externalId, String displayName, String accessToken,
                        String refreshToken, Instant expiresAt) {}

    /** What the runtime hands back when publishing: the stored account with its decrypted token. */
    record ChannelAccountRef(String network, String externalId, String displayName, String accessToken) {}

    /**
     * A post ready for one network. Each image is offered both as bytes (platforms that take an
     * upload) and as a public, expiring JPEG URL (platforms that fetch it themselves).
     */
    record ChannelPost(String text, List<Media> media) {
        public ChannelPost {
            media = media == null ? List.of() : List.copyOf(media);
        }
    }

    record Media(String contentType, byte[] bytes, String publicUrl) {}

    /** The published post: the platform's id and, where it has one, a link to it. */
    record PublishResult(String externalPostId, String url) {}
}
