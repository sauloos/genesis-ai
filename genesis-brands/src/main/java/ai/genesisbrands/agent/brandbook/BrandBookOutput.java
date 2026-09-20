package ai.genesisbrands.agent.brandbook;

import java.util.List;

public record BrandBookOutput(
    String engagementId,
    String welcomeNote,
    String welcomeLetterOpening,
    String howToUseThisGuide,
    String logoUsageGuidelines,
    List<String> logoDonts,
    String colorUsageGuidelines,
    String typographyUsageGuidelines,
    String typefaceRationale,
    String imageryGuidance,
    String closingNote,
    String reasoning,
    int iteration
) {}
