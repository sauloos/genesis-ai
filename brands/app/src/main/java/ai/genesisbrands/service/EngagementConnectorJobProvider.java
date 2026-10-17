package ai.genesisbrands.service;

import ai.genesisbrands.model.AgentRegenerationJob;
import ai.genesisbrands.platform.connector.ConnectorJob;
import ai.genesisbrands.platform.connector.ConnectorJobProvider;
import ai.genesisbrands.repository.AgentRegenerationJobRepository;
import ai.genesisbrands.repository.EngagementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Genesis Brands' {@link ConnectorJobProvider}: runs the SLOT agents (Copy, Visual Identity,
 * Logo, Playbook, Brand Book) as Agent connector jobs on the client's own finished engagement
 * and chosen direction — the same approval-gated regeneration, cap and in-flight lock as the
 * Live Dashboard, since it goes through {@link AgentRegenerationService}.
 */
@Component
@RequiredArgsConstructor
public class EngagementConnectorJobProvider implements ConnectorJobProvider {

    private final AgentRegenerationService regeneration;
    private final AgentRegenerationJobRepository jobRepo;
    private final EngagementRepository engagementRepo;
    private final EngagementOwnSubjectResolver ownEngagement;

    @Override
    public boolean supports(String agentId) {
        return regeneration.supportsRegeneration(agentId);
    }

    @Override
    public ConnectorJob start(String agentId, String clientUserId, String feedback) {
        var engagement = ownEngagement.findOwnEngagement(clientUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No finished, paid engagement to regenerate yet"));
        return toJob(regeneration.requestRegeneration(engagement.getId(), engagement.getChosenDirection(), agentId, feedback, clientUserId));
    }

    @Override
    public Optional<ConnectorJob> find(String jobId, String clientUserId) {
        return jobRepo.findById(jobId)
            .filter(job -> engagementRepo.findById(job.getEngagementId())
                .map(e -> clientUserId.equals(e.getClientUserId()))
                .orElse(false))
            .map(this::toJob);
    }

    @Override
    public ConnectorJob approve(String jobId, String clientUserId) {
        return toJob(regeneration.approve(jobId, clientUserId));
    }

    @Override
    public ConnectorJob reject(String jobId, String clientUserId) {
        return toJob(regeneration.reject(jobId, clientUserId));
    }

    private ConnectorJob toJob(AgentRegenerationJob job) {
        return new ConnectorJob(job.getId(), job.getAgentId(), job.getStatus().name(), job.getErrorMessage(),
            job.getStatus() == AgentRegenerationJob.Status.AWAITING_APPROVAL ? job.getDraftOutputJson() : null,
            job.getCreatedAt(), job.getCompletedAt());
    }
}
