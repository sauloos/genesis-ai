package ai.genesisbrands.agent.brandbook;

import java.util.List;

public record BaselineOutput(
    String welcomeNote,
    String howToUseThisGuide,
    String logoUsageGuidelines,
    List<String> logoDonts,
    String colorUsageGuidelines,
    String typographyUsageGuidelines,
    String closingNote
) {}
