package ai.genesisbrands.usage;

import java.util.function.Supplier;

/**
 * Who a model call is billed to. {@code clientUserId} is a supplier so a request scope can
 * defer the session lookup until a model call actually happens (most requests never make one).
 * Any field may be null: an admin playground call has no client, a web request has no run.
 */
public record UsageScope(Supplier<String> clientUserId, String agentId, String source, String runId) {

    /** Request attribute a filter sets when it has already resolved the calling client (connectors). */
    public static final String CLIENT_USER_ATTRIBUTE = UsageScope.class.getName() + ".clientUserId";

    public static UsageScope of(String clientUserId, String agentId, String source, String runId) {
        return new UsageScope(() -> clientUserId, agentId, source, runId);
    }

    /** A copy with the client resolved now, safe to keep after the request that opened it ends. */
    public UsageScope pinned() {
        return of(resolveClientUserId(), agentId, source, runId);
    }

    public String resolveClientUserId() {
        try {
            return clientUserId == null ? null : clientUserId.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
