package ai.genesisbrands.agent.logo;

public record BaselineLogoOutput(
    LogoOutput.Method method,
    String conceptDescription,
    String imagePrompt,   // DALLE method only
    String imageUrl,      // DALLE method only
    String svgMarkup      // SVG_CONCEPT method only
) {}
