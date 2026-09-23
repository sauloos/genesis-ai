package ai.genesisbrands.service;

import ai.genesisbrands.model.AgentCatalogConfig;
import ai.genesisbrands.platform.AgentCustomOption;
import ai.genesisbrands.platform.CoreAgent;
import ai.genesisbrands.repository.AgentCatalogConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Joins the registered CoreAgent beans (descriptor, fixed in code) with their
 * admin-set AgentCatalogConfig row (persisted, may not exist yet) into one view.
 * A missing config row means "use the defaults", not "not yet added" — see
 * AgentCatalogConfig's javadoc.
 */
@Service
@RequiredArgsConstructor
public class AgentCatalogService {

    private final List<CoreAgent> agents;
    private final AgentCatalogConfigRepository configRepository;

    public List<AgentCatalogEntry> listCatalog() {
        return agents.stream().map(this::toEntry).toList();
    }

    @Transactional
    public AgentCatalogEntry updateConfig(String agentId, UpdateAgentConfigRequest req) {
        CoreAgent agent = findAgent(agentId);

        if (Boolean.TRUE.equals(req.abCompareEnabled()) && !agent.supportsABCompare()) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' does not support A/B compare");
        }

        AgentCatalogConfig config = configRepository.findById(agentId).orElseGet(() -> {
            AgentCatalogConfig fresh = new AgentCatalogConfig();
            fresh.setAgentId(agentId);
            return fresh;
        });

        if (req.availableForLiveView() != null) config.setAvailableForLiveView(req.availableForLiveView());
        if (req.availableForPlayground() != null) config.setAvailableForPlayground(req.availableForPlayground());
        if (req.abCompareEnabled() != null) config.setAbCompareEnabled(req.abCompareEnabled());
        config.setUpdatedAt(Instant.now());

        configRepository.save(config);
        return toEntry(agent);
    }

    @Transactional
    public void resetConfig(String agentId) {
        findAgent(agentId);
        configRepository.deleteById(agentId);
    }

    private CoreAgent findAgent(String agentId) {
        return agents.stream()
            .filter(a -> a.agentId().equals(agentId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("No such agent: " + agentId));
    }

    private AgentCatalogEntry toEntry(CoreAgent agent) {
        Optional<AgentCatalogConfig> config = configRepository.findById(agent.agentId());
        return new AgentCatalogEntry(
            agent.agentId(),
            agent.displayName(),
            agent.description(),
            agent.testEndpoint(),
            agent.requiresQuestionnaire(),
            agent.supportsPlayground(),
            agent.supportsABCompare(),
            agent.customOptions(),
            config.map(AgentCatalogConfig::isAvailableForLiveView).orElse(true),
            config.map(AgentCatalogConfig::isAvailableForPlayground).orElse(true),
            config.map(AgentCatalogConfig::isAbCompareEnabled).orElse(false)
        );
    }

    public record AgentCatalogEntry(
        String agentId, String displayName, String description, String testEndpoint,
        boolean requiresQuestionnaire, boolean supportsPlayground, boolean supportsABCompare,
        List<AgentCustomOption> customOptions,
        boolean availableForLiveView, boolean availableForPlayground, boolean abCompareEnabled
    ) {}

    public record UpdateAgentConfigRequest(
        Boolean availableForLiveView, Boolean availableForPlayground, Boolean abCompareEnabled
    ) {}
}
