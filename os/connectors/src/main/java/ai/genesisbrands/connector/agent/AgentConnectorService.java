package ai.genesisbrands.connector.agent;

import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.model.GeneratedDocument;
import ai.genesisbrands.platform.connector.ConnectorChatProvider;
import ai.genesisbrands.platform.connector.ConnectorJob;
import ai.genesisbrands.platform.connector.ConnectorJobProvider;
import ai.genesisbrands.repository.ClientUserRepository;
import ai.genesisbrands.service.AgentCatalogService;
import ai.genesisbrands.service.AgentCatalogService.AgentCatalogEntry;
import ai.genesisbrands.service.ClientOwnSubjectResolver;
import ai.genesisbrands.service.GeneratedDocumentService;
import ai.genesisbrands.service.GenericAgentChatService;
import ai.genesisbrands.service.GenericSingleShotAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Agent connectors, generated from the agent catalog rather than written per agent: every
 * registered {@code CoreAgent} that an admin has opened to connectors is reachable here, in
 * whichever mode fits how it actually runs —
 * <ul>
 *   <li>{@link Mode#JOB} — a {@link ConnectorJobProvider} runs it (SLOT agents: start, poll,
 *       approve/reject);</li>
 *   <li>{@link Mode#CHAT} — a {@link ConnectorChatProvider} handles it (e.g. Consultant), or it
 *       is a generic chat agent needing no engagement;</li>
 *   <li>{@link Mode#GENERATE} — a generic single-shot agent needing no engagement; each call
 *       is saved as one of the client's generated documents, exactly like the Live Dashboard.</li>
 * </ul>
 * An agent fitting none of these has no connector shape and is never listed. The caller's
 * {@code clientUserId} always comes from the connector key, never the request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConnectorService {

    private static final Duration CHAT_TIMEOUT = Duration.ofMinutes(3);

    private final AgentCatalogService catalog;
    private final List<ConnectorJobProvider> jobProviders;
    private final List<ConnectorChatProvider> chatProviders;
    private final GenericAgentChatService genericChat;
    private final GenericSingleShotAgentService genericGeneration;
    private final GeneratedDocumentService documents;
    private final ClientUserRepository clientUsers;
    private final Optional<ClientOwnSubjectResolver> subjectResolver;

    public enum Mode { JOB, CHAT, GENERATE }

    public record ConnectableAgent(String agentId, String displayName, String description, Mode mode) {}

    /** Every agent this deployment exposes to connectors, with the mode it runs in. */
    public List<ConnectableAgent> list() {
        return catalog.listCatalog().stream()
            .filter(e -> catalog.isAvailableForConnectors(e.agentId()))
            .flatMap(e -> modeOf(e).map(m -> new ConnectableAgent(e.agentId(), e.displayName(), e.description(), m)).stream())
            .toList();
    }

    /** The agent if it is exposed to connectors and runs in {@code expected} mode; 404 otherwise. */
    public ConnectableAgent require(String agentId, Mode expected) {
        catalog.requireAvailableForConnectors(agentId);
        ConnectableAgent agent = list().stream()
            .filter(a -> a.agentId().equals(agentId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not available: " + agentId));
        if (agent.mode() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Agent '" + agentId + "' runs as " + agent.mode().name().toLowerCase() + ", not " + expected.name().toLowerCase());
        }
        return agent;
    }

    public String chat(String agentId, String clientUserId, String message) {
        require(agentId, Mode.CHAT);
        requireText(message, "message");
        Optional<ConnectorChatProvider> provider = chatProviders.stream().filter(p -> p.supports(agentId)).findFirst();
        if (provider.isPresent()) {
            return upstream(agentId, () -> provider.get().reply(agentId, clientUserId, message));
        }
        String reply = upstream(agentId, () -> genericChat
            .chat(agentId, clientUserId, message, ConversationMessage.Source.CUSTOMER, liveSubjectId(clientUserId))
            .collectList()
            .map(chunks -> String.join("", chunks))
            .block(CHAT_TIMEOUT));
        return reply == null ? "" : reply;
    }

    public GeneratedDocument generate(String agentId, String clientUserId, String input, String title) {
        require(agentId, Mode.GENERATE);
        requireText(input, "input");
        String output = upstream(agentId, () -> genericGeneration.generate(agentId, input, liveSubjectId(clientUserId)));
        return documents.save(agentId, clientUserId, title, output);
    }

    /** Runs the agent; deliberate HTTP errors pass through, a failing model or agent becomes 502. */
    private <T> T upstream(String agentId, Supplier<T> call) {
        try {
            return call.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Connector call to agent '{}' failed: {}", agentId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent '" + agentId + "' failed to respond");
        }
    }

    public ConnectorJob startJob(String agentId, String clientUserId, String feedback) {
        require(agentId, Mode.JOB);
        return jobProvider(agentId).start(agentId, clientUserId, feedback == null || feedback.isBlank() ? null : feedback);
    }

    public ConnectorJob findJob(String jobId, String clientUserId) {
        return ownJob(jobId, clientUserId).job();
    }

    public ConnectorJob approveJob(String jobId, String clientUserId) {
        OwnedJob owned = ownJob(jobId, clientUserId);
        return owned.provider().approve(jobId, clientUserId);
    }

    public ConnectorJob rejectJob(String jobId, String clientUserId) {
        OwnedJob owned = ownJob(jobId, clientUserId);
        return owned.provider().reject(jobId, clientUserId);
    }

    private record OwnedJob(ConnectorJobProvider provider, ConnectorJob job) {}

    /**
     * Finds the client's own job across providers, then re-checks that its agent is still
     * open to connectors — an admin closing an agent closes its in-flight jobs too.
     */
    private OwnedJob ownJob(String jobId, String clientUserId) {
        for (ConnectorJobProvider provider : jobProviders) {
            Optional<ConnectorJob> job = provider.find(jobId, clientUserId);
            if (job.isPresent()) {
                catalog.requireAvailableForConnectors(job.get().agentId());
                return new OwnedJob(provider, job.get());
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    }

    private ConnectorJobProvider jobProvider(String agentId) {
        return jobProviders.stream().filter(p -> p.supports(agentId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not available: " + agentId));
    }

    private Optional<Mode> modeOf(AgentCatalogEntry e) {
        String id = e.agentId();
        if (jobProviders.stream().anyMatch(p -> p.supports(id))) return Optional.of(Mode.JOB);
        if (chatProviders.stream().anyMatch(p -> p.supports(id))) return Optional.of(Mode.CHAT);
        if (e.requiresEngagementContext()) return Optional.empty();
        return Optional.of(e.chatBased() ? Mode.CHAT : Mode.GENERATE);
    }

    /** Live brand context is enrichment only — none when no resolver or no own subject yet. */
    private String liveSubjectId(String clientUserId) {
        if (subjectResolver.isEmpty()) return null;
        return clientUsers.findById(clientUserId)
            .flatMap(client -> subjectResolver.get().resolveOwnSubjectId(client))
            .orElse(null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + field + "' is required");
        }
    }
}
