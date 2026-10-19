package ai.genesisbrands.platform.channel;

/**
 * A platform refused or failed a call. The message is shown to the client, so it says what
 * went wrong in their terms. {@code retryable} marks failures a later attempt may fix (rate
 * limits, timeouts, 5xx); {@code reauthorize} marks a revoked or expired token, which needs
 * the client to connect again.
 */
public class ChannelException extends RuntimeException {

    private final boolean retryable;
    private final boolean reauthorize;

    public ChannelException(String message, boolean retryable, boolean reauthorize) {
        super(message);
        this.retryable = retryable;
        this.reauthorize = reauthorize;
    }

    public static ChannelException permanent(String message) {
        return new ChannelException(message, false, false);
    }

    public static ChannelException retryable(String message) {
        return new ChannelException(message, true, false);
    }

    public static ChannelException reauthorize(String message) {
        return new ChannelException(message, false, true);
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean reauthorize() {
        return reauthorize;
    }
}
