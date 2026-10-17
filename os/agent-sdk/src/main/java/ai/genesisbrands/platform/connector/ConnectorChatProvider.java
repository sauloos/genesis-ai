package ai.genesisbrands.platform.connector;

/**
 * Extension point: lets the Agent connectors reach a chat agent whose customer conversation
 * isn't the generic one (e.g. Consultant, which grounds every turn in the client's own
 * subject). Generic chat agents need no provider — the gateway handles them itself.
 * <p>
 * {@code clientUserId} is resolved server-side from the connector key; an implementation
 * derives any brand/subject context from it, never from the message.
 */
public interface ConnectorChatProvider {

    /** True when this provider handles {@code agentId}'s customer chat. */
    boolean supports(String agentId);

    /** Sends one message on the client's own connector thread and returns the full reply. */
    String reply(String agentId, String clientUserId, String message);
}
