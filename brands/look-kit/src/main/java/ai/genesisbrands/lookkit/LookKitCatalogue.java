package ai.genesisbrands.lookkit;

import java.util.List;
import java.util.Map;

/**
 * The look-and-feel asset kit as data: the eight forced-choice pairs, the nine palettes and the
 * four type specimens the discovery questionnaire points a client at.
 *
 * <p>Everything here is presentation and fact — what the client sees, and the hexes and faces an
 * agent needs to actually produce a palette or set a word. What a choice <em>means</em> is
 * reasoning and lives in Layer 1 ({@code directions/look-and-feel-kit.yaml}), keyed by the same
 * values, so a change of interpretation never means a code change.
 */
public record LookKitCatalogue(
    int version,
    List<Pair> pairs,
    List<Palette> palettes,
    /**
     * A slider whose scale is a real spectrum, by signal key: "warm … cool" means nothing as two
     * words, so the questionnaire draws the gradient under the handle from these colours.
     */
    Map<String, List<String>> spectra,
    List<TypeSpecimen> typeSpecimens
) {

    /**
     * One half of a forced choice. {@code label} is what the client reads and never names a
     * design attribute; {@code form} is the design term, carried for the agents and the designer.
     */
    public record Tile(String value, String label, String form, String imageUrl) {}

    /** A forced choice on one axis. There is no neutral option: the signal lives in the choice. */
    public record Pair(String axis, String signalKey, Tile a, Tile b) {
        public List<Tile> tiles() {
            return List.of(a, b);
        }
    }

    /**
     * A palette the client can accept or reject whole. {@code colors} is the strip they see;
     * {@code roles} is the same set addressed by job (ground, support, primary, secondary, ink),
     * so an agent never has to guess a role from a position in the strip.
     */
    public record Palette(String value, String label, String note,
                          List<String> colors, Map<String, String> roles) {}

    /** A face, set in the client's own sentence — {@code sampleText} holds a {signal.key}. */
    public record TypeSpecimen(String value, String label, String form, String fontFamily,
                               String sampleText, String fallbackText) {}
}
