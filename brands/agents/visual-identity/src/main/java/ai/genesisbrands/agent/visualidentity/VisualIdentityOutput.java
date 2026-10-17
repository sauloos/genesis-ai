package ai.genesisbrands.agent.visualidentity;

import ai.genesisbrands.service.ColorSpecificationService;

import java.util.List;

public record VisualIdentityOutput(
    String engagementId,
    List<ColorSwatch> colorPalette,
    Typography typography,
    String moodDirection,
    List<GradientPair> gradients,
    GraphicDevicesSpec graphicDevices,
    List<String> imageKeywords,
    String reasoning,
    int iteration
) {
    /**
     * CMYK, Pantone, and RGB are computed server-side via ColorSpecificationService
     * after parse — Claude only outputs hex. These fields are null for baseline output.
     */
    public record ColorSwatch(
        String name,
        String hex,
        String role,
        String rationale,
        ColorSpecificationService.CmykValues cmyk,
        String pantone,
        ColorSpecificationService.RgbValues rgb
    ) {
        public ColorSwatch(String name, String hex, String role, String rationale) {
            this(name, hex, role, rationale, null, null, null);
        }
    }

    public record Typography(
        String headlineFont,
        String bodyFont,
        String pairingRationale
    ) {}

    public record GradientPair(
        String colorA,
        String colorB,
        String name,
        String usage
    ) {}

    public record GraphicDevicesSpec(
        List<String> strokeVariants,
        List<String> boxVariants,
        String iconUsageNote
    ) {}
}
