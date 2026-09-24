package ai.genesisbrands.agent.consultant;

import ai.genesisbrands.platform.CoreAgent;

/**
 * Registers Consultant in the Agents catalog so it gets the same admin-configurable
 * availableForLiveView / availableForPlayground toggles as every specialist agent —
 * see {@link ai.genesisbrands.config.ConsultantServiceConfiguration}, which is the only
 * place this is instantiated (conditioned on a tenant's ConsultantSubjectProvider bean,
 * same as ConsultantService/ConsultantController).
 */
public class ConsultantCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "consultant";
    }

    @Override
    public String displayName() {
        return "Consultant";
    }

    @Override
    public String description() {
        return "Conversational creative-director interface — brand strategy Q&A over the full knowledge base, scoped to a brand.";
    }

    @Override
    public String testEndpoint() {
        return "/api/consultant/subjects";
    }

    @Override
    public boolean chatBased() {
        return true;
    }
}
