package ai.genesisbrands.config;

import ai.genesisbrands.model.AgentCatalogConfig;
import ai.genesisbrands.repository.AgentCatalogConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Genesis Brands does not use the six generic GenesisOS utility agents (Summarizer,
 * Support/FAQ, Compliance Q&A, RFP Response, Research & Synthesis, Writer) by default —
 * they remain registered (so an admin can still see and enable them) but start toggled
 * off for both Playground and Live, per the user's explicit "unselected for playground
 * and for live" requirement. Idempotent: only inserts a row when none exists yet, so an
 * admin's later re-enable (which also leaves a row) is never overwritten on restart.
 * genesis-os intentionally has no equivalent seeder — absence of a row there keeps
 * {@code AgentCatalogService}'s default of available=true, so these agents are
 * enabled out-of-the-box on Genesis OS.
 */
@Component
@RequiredArgsConstructor
public class NewAgentCatalogDefaultsSeeder implements ApplicationRunner {

    private static final List<String> DEFAULT_OFF_AGENT_IDS = List.of(
        "summarizer", "support-faq", "compliance-qa", "rfp-response", "research-synthesis", "writer"
    );

    private final AgentCatalogConfigRepository configRepository;

    @Override
    public void run(ApplicationArguments args) {
        for (String agentId : DEFAULT_OFF_AGENT_IDS) {
            if (configRepository.existsById(agentId)) continue;

            AgentCatalogConfig config = new AgentCatalogConfig();
            config.setAgentId(agentId);
            config.setAvailableForLiveView(false);
            config.setAvailableForPlayground(false);
            configRepository.save(config);
        }
    }
}
