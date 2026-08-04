package ai.genesisbrands.agent.visualidentity;

import java.util.List;

public record VisualIdentityOutput(
    String engagementId,
    List<ColorSwatch> colorPalette,
    Typography typography,
    String moodDirection,
    String reasoning,
    int iteration
) {
    public record ColorSwatch(
        String name,
        String hex,
        String role,
        String rationale
    ) {}

    public record Typography(
        String headlineFont,
        String bodyFont,
        String pairingRationale
    ) {}
}
