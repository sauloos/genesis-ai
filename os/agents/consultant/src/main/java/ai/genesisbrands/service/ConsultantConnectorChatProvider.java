package ai.genesisbrands.service;

import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.platform.connector.ConnectorChatProvider;
import ai.genesisbrands.repository.ClientUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

/**
 * The Consultant connector: the customer-aware consultant, reached from a client's own model
 * or app. Grounded exactly like the Live Dashboard chat — the subject comes from the tenant's
 * {@link ClientOwnSubjectResolver} for the client the connector key belongs to, never from
 * the caller. All connector turns share one conversation, kept apart from the client's
 * dashboard conversations. Registered only where that resolver exists (see
 * {@code ConsultantServiceConfiguration}); elsewhere Consultant has no connector.
 */
@RequiredArgsConstructor
public class ConsultantConnectorChatProvider implements ConnectorChatProvider {

    private static final String CONSULTANT_AGENT_ID = "consultant";
    private static final String CONNECTOR_CONVERSATION_ID = "connector";
    private static final Duration REPLY_TIMEOUT = Duration.ofMinutes(3);

    private final ConsultantService consultant;
    private final ClientOwnSubjectResolver subjectResolver;
    private final ClientUserRepository clientUsers;

    @Override
    public boolean supports(String agentId) {
        return CONSULTANT_AGENT_ID.equals(agentId);
    }

    @Override
    public String reply(String agentId, String clientUserId, String message) {
        String subjectId = clientUsers.findById(clientUserId)
            .flatMap(subjectResolver::resolveOwnSubjectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No consultant subject is available for this account yet"));
        String reply = consultant
            .chat(subjectId, CONNECTOR_CONVERSATION_ID, message, null, List.of(), ConversationMessage.Source.CUSTOMER)
            .collectList()
            .map(chunks -> String.join("", chunks))
            .block(REPLY_TIMEOUT);
        return reply == null ? "" : reply;
    }
}
