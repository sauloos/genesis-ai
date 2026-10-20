package ai.genesisbrands.gardener;

import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

/**
 * The gardener as a catalog agent, so its model, token limit and prompt are configured like any
 * other agent's (agent.yml, system.md, admin overrides). It runs only from the ingestion hook and
 * the admin page, never for clients.
 */
@Component
public class KnowledgeGardenerAgent implements CoreAgent {

    public static final String ID = "knowledge-gardener";

    @Override
    public String agentId() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Knowledge gardener";
    }

    @Override
    public String description() {
        return "Reads each source newly ingested into Layer 1 and flags where it contradicts or extends the reasoning modules.";
    }

    @Override
    public String icon() {
        return "🌱";
    }

    @Override
    public String testEndpoint() {
        return "/api/admin/knowledge/gardener";
    }

    @Override
    public boolean supportsPlayground() {
        return false;
    }

    @Override
    public boolean requiresEngagementContext() {
        return false;
    }
}
