package ai.genesisbrands.agent.playbook;

import java.util.List;

public record BaselineOutput(
    String strategicSummary,
    String brandFoundations,
    String verbalIdentity,
    String visualIdentitySummary,
    String logoRationale,
    List<String> recommendedApplications
) {}
