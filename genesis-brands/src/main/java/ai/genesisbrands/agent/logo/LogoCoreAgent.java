package ai.genesisbrands.agent.logo;

import ai.genesisbrands.platform.AgentCustomOption;
import ai.genesisbrands.platform.AgentCustomOption.OptionType;
import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LogoCoreAgent implements CoreAgent {

    @Override
    public String agentId() {
        return "logo";
    }

    @Override
    public String displayName() {
        return "Logo Agent";
    }

    @Override
    public String description() {
        return "Logo mark concept.";
    }

    @Override
    public String testEndpoint() {
        return "/api/agents/logo/execute";
    }

    @Override
    public boolean supportsABCompare() {
        return true;
    }

    /**
     * Logo's method choice supports picking up to 2 values at once (for a side-by-side
     * method compare, distinct from the generic AB-compare toggle) — Playground renders
     * this one specially rather than through the generic single-value options form, but
     * sources its button list from allowedValues() here instead of a hardcoded array.
     */
    @Override
    public List<AgentCustomOption> customOptions() {
        return List.of(new AgentCustomOption(
            "method",
            "Generation method",
            OptionType.SELECT,
            List.of("DALLE", "IDEOGRAM", "SVG_CONCEPT"),
            "DALLE"
        ));
    }
}
