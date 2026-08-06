package ai.genesisbrands.agent.playbook;

import java.util.List;

public record PlaybookOutput(
    String engagementId,
    String strategicSummary,
    String brandFoundations,
    String verbalIdentity,
    String visualIdentitySummary,
    String logoRationale,
    List<String> recommendedApplications,
    String reasoning,
    int iteration
) {}
