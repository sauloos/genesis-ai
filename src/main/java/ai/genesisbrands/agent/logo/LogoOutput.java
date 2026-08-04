package ai.genesisbrands.agent.logo;

public record LogoOutput(
    String engagementId,
    Method method,
    String conceptDescription,
    String symbolism,
    String imagePrompt,   // DALLE method only
    String imageUrl,      // DALLE method only — servable asset URL
    String svgMarkup,     // SVG_CONCEPT method only
    String reasoning,
    int iteration
) {
    public enum Method { DALLE, SVG_CONCEPT }
}
