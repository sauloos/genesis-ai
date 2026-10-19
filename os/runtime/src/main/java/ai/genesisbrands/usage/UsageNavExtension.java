package ai.genesisbrands.usage;

import ai.genesisbrands.platform.AdminNavExtension;
import org.springframework.stereotype.Component;

@Component
public class UsageNavExtension implements AdminNavExtension {

    @Override
    public String label() {
        return "Usage";
    }

    @Override
    public String path() {
        return "/dashboard/usage";
    }

    @Override
    public String description() {
        return "Model spend by client, agent and model. Every agent call is metered here.";
    }
}
