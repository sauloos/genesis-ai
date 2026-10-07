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

    /** Public-safe view for client-facing widgets: only agents an admin has enabled for live view. */
    public List<PublicAgentEntry> listAvailableForLiveView() {
        return agents.stream()
            .map(this::toEntry)
            .filter(AgentCatalogEntry::availableForLiveView)
            .map(e -> new PublicAgentEntry(e.agentId(), e.displayName(), e.description(), e.icon(), e.chatBased(), e.hasLiveView(), e.requiresEngagementContext()))
            .toList();
    }

    /** Cheap standalone check for a single agent's toolsEnabled flag — used by ConsultantService
     *  on every chat turn, so it skips building the full catalog via toEntry(). */
    public boolean isToolsEnabled(String agentId) {
        return configRepository.findById(agentId)
            .map(AgentCatalogConfig::isToolsEnabled)
            .orElse(false);
    }

    @Transactional
    public AgentCatalogEntry updateConfig(String agentId, UpdateAgentConfigRequest req) {
        CoreAgent agent = findAgent(agentId);

        if (Boolean.TRUE.equals(req.abCompareEnabled()) && !agent.supportsABCompare()) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' does not support A/B compare");
        }
        if (Boolean.TRUE.equals(req.toolsEnabled()) && !agent.supportsToolUse()) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' does not support tool use");
        }

        AgentCatalogConfig config = configRepository.findById(agentId).orElseGet(() -> {
            AgentCatalogConfig fresh = new AgentCatalogConfig();
            fresh.setAgentId(agentId);
            return fresh;
        });

        if (req.availableForLiveView() != null) config.setAvailableForLiveView(req.availableForLiveView());
        if (req.availableForPlayground() != null) config.setAvailableForPlayground(req.availableForPlayground());
        if (req.abCompareEnabled() != null) config.setAbCompareEnabled(req.abCompareEnabled());
        if (req.toolsEnabled() != null) config.setToolsEnabled(req.toolsEnabled());
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
            agent.icon(),
            agent.requiresQuestionnaire(),
            agent.supportsPlayground(),
            agent.supportsABCompare(),
            agent.customOptions(),
            config.map(AgentCatalogConfig::isAvailableForLiveView).orElse(true),
            config.map(AgentCatalogConfig::isAvailableForPlayground).orElse(true),
            config.map(AgentCatalogConfig::isAbCompareEnabled).orElse(false),
            agent.chatBased(),
            agent.hasLiveView(),
            agent.supportsToolUse(),
            config.map(AgentCatalogConfig::isToolsEnabled).orElse(false),
            agent.requiresEngagementContext(),
            agent.hasPlaygroundView()
        );
    }

    public record AgentCatalogEntry(
        String agentId, String displayName, String description, String testEndpoint, String icon,
        boolean requiresQuestionnaire, boolean supportsPlayground, boolean supportsABCompare,
        List<AgentCustomOption> customOptions,
        boolean availableForLiveView, boolean availableForPlayground, boolean abCompareEnabled,
        boolean chatBased, boolean hasLiveView,
        boolean supportsToolUse, boolean toolsEnabled,
        boolean requiresEngagementContext, boolean hasPlaygroundView
    ) {}

    public record UpdateAgentConfigRequest(
        Boolean availableForLiveView, Boolean availableForPlayground, Boolean abCompareEnabled, Boolean toolsEnabled
    ) {}

    public record PublicAgentEntry(
        String agentId, String displayName, String description, String icon,
        boolean chatBased, boolean hasLiveView, boolean requiresEngagementContext
    ) {}
}
