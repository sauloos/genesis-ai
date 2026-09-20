package ai.genesisbrands.agent.visualidentity;

import java.util.List;

public record BaselineVisualIdentityOutput(
    List<VisualIdentityOutput.ColorSwatch> colorPalette,
    VisualIdentityOutput.Typography typography,
    String moodDirection
) {}
