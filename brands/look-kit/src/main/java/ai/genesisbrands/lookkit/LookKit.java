package ai.genesisbrands.lookkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Resolves a stored look-and-feel answer back into the thing the client actually tapped.
 *
 * <p>A questionnaire answer is only a value — {@code "warm-earth"}, {@code "wordmark-serif"}. The
 * seed script authors the questions from this catalogue and the agents read it back: the
 * purpose-playbook agent to say in the playbook what the client chose, the visual-identity agent
 * to get real hexes and roles, the logo agent to get the mark's form. Without it, a brief would
 * carry a slug no model can act on.
 *
 * <p>Deliberately a resolver and nothing more. It states facts about the kit; the meaning of a
 * choice is Layer 1's job ({@code knowledge/layer1/modules/directions/look-and-feel-kit.yaml}).
 */
@Component
public class LookKit {

    private static final String RESOURCE = "/static/look-kit/catalogue.json";

    private final LookKitCatalogue catalogue;

    public LookKit() {
        this.catalogue = load();
    }

    /** Reads the kit off the classpath. Static so a test or a script needs no Spring context. */
    public static LookKitCatalogue load() {
        ObjectMapper mapper = new ObjectMapper()
            // The catalogue carries an `about` block for whoever opens the file; it is
            // documentation, not data, and must not fail the load.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (InputStream in = LookKit.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Look kit catalogue missing from the classpath: " + RESOURCE);
            }
            return mapper.readValue(in, LookKitCatalogue.class);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Look kit catalogue could not be read: " + RESOURCE, e);
        }
    }

    public LookKitCatalogue catalogue() {
        return catalogue;
    }

    public Optional<LookKitCatalogue.Pair> pair(String axis) {
        return catalogue.pairs().stream().filter(p -> p.axis().equals(axis)).findFirst();
    }

    /** Either half of any pair, by the value the client's answer holds. */
    public Optional<LookKitCatalogue.Tile> tile(String value) {
        return catalogue.pairs().stream()
            .flatMap(p -> p.tiles().stream())
            .filter(t -> t.value().equals(value))
            .findFirst();
    }

    public Optional<LookKitCatalogue.Palette> palette(String value) {
        return catalogue.palettes().stream().filter(p -> p.value().equals(value)).findFirst();
    }

    public Optional<LookKitCatalogue.TypeSpecimen> typeSpecimen(String value) {
        return catalogue.typeSpecimens().stream().filter(t -> t.value().equals(value)).findFirst();
    }

    /**
     * The design term behind any stored value — a tile, a palette or a specimen. This is what a
     * brief should carry instead of the slug: "serif wordmark", not "wordmark-serif".
     */
    public Optional<String> formOf(String value) {
        Optional<String> fromTile = tile(value).map(LookKitCatalogue.Tile::form);
        if (fromTile.isPresent()) {
            return fromTile;
        }
        Optional<String> fromSpecimen = typeSpecimen(value).map(LookKitCatalogue.TypeSpecimen::form);
        if (fromSpecimen.isPresent()) {
            return fromSpecimen;
        }
        return palette(value).map(p -> p.label() + " (" + p.note() + ")");
    }
}
