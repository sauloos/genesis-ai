package ai.genesisbrands.agent.logo;

import java.util.List;

/**
 * The visual identity a logo must belong to: the palette and typefaces the Visual Identity agent
 * chose for the same direction. The orchestrator builds it from that agent's output (the logo
 * module does not depend on the visual-identity module), so a lockup's wordmark is set in the
 * direction's own type and colours rather than whatever the image model reaches for.
 * Null when there is none yet (Playground, a direct API call).
 */
public record LogoIdentity(
    List<Swatch> palette,
    String headlineFont,
    String bodyFont,
    String mood
) {
    public record Swatch(String name, String hex, String role) {}

    public LogoIdentity {
        palette = palette == null ? List.of() : List.copyOf(palette);
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        if (!palette.isEmpty()) {
            sb.append("Palette:\n");
            palette.forEach(s -> sb.append("- ").append(s.name()).append(" ").append(s.hex())
                .append(s.role() == null || s.role().isBlank() ? "" : " (" + s.role() + ")").append("\n"));
        }
        if (headlineFont != null && !headlineFont.isBlank()) {
            sb.append("Headline typeface: ").append(headlineFont).append("\n");
        }
        if (bodyFont != null && !bodyFont.isBlank()) {
            sb.append("Body typeface: ").append(bodyFont).append("\n");
        }
        if (mood != null && !mood.isBlank()) {
            sb.append("Mood: ").append(mood).append("\n");
        }
        return sb.toString();
    }
}
