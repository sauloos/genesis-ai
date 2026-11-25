package ai.genesisbrands.service;

import ai.genesisbrands.agent.logo.LogoIdentity;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;

/**
 * Hands the Visual Identity agent's palette and typefaces to the Logo agent, so a direction's
 * lockup is set in that direction's own type and colours. The logo module does not depend on the
 * visual-identity module; the app, which has both, translates between them.
 */
final class LogoIdentities {

    private LogoIdentities() {}

    static LogoIdentity from(VisualIdentityOutput visual) {
        if (visual == null) return null;
        var palette = visual.colorPalette() == null ? java.util.List.<LogoIdentity.Swatch>of()
            : visual.colorPalette().stream()
                .filter(s -> s != null && s.hex() != null)
                .map(s -> new LogoIdentity.Swatch(s.name(), s.hex(), s.role()))
                .toList();
        var type = visual.typography();
        return new LogoIdentity(palette,
            type == null ? null : type.headlineFont(),
            type == null ? null : type.bodyFont(),
            visual.moodDirection());
    }
}
