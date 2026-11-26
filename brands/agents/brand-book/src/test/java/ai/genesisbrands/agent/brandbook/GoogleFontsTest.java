package ai.genesisbrands.agent.brandbook;

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleFontsTest {

    /** A fake css2: the families it knows and the weights each has; italic for none. */
    private static GoogleFonts fonts(Map<String, List<Integer>> families) {
        return new GoogleFonts(url -> {
            String q = url.substring(url.indexOf("family=") + "family=".length(), url.indexOf("&display"));
            String[] parts = URLDecoder.decode(q, StandardCharsets.UTF_8).split(":", 2);
            List<Integer> weights = families.get(parts[0]);
            if (weights == null) return 400;
            if (parts.length == 1) return 200;
            if (parts[1].startsWith("wght@")) return weights.contains(Integer.parseInt(parts[1].substring(5))) ? 200 : 400;
            return 400;
        });
    }

    @Test
    void cleansStacksAndNotes() {
        assertThat(GoogleFonts.sanitise("Inter (Variable)")).isEqualTo("Inter");
        assertThat(GoogleFonts.sanitise("'Söhne', sans-serif")).isEqualTo("Söhne");
        assertThat(GoogleFonts.sanitise("Lora / Georgia")).isEqualTo("Lora");
    }

    @Test
    void dropsAStyleWordTheFamilyDoesNotHave() {
        GoogleFonts f = fonts(Map.of("Archivo", List.of(300, 400, 700, 900)));
        GoogleFonts.Face face = f.resolve("Archivo Expanded", GoogleFonts.FALLBACK_BODY);
        assertThat(face.family()).isEqualTo("Archivo");
        assertThat(face.substituted()).isTrue();
        assertThat(face.weights()).containsExactly(300, 400, 700, 900);
        assertThat(face.specimenWeights()).containsExactly(300, 400, 700);
    }

    @Test
    void keepsAFamilyWhoseNameEndsInAWeightWord() {
        GoogleFonts f = fonts(Map.of("Archivo Black", List.of(400)));
        GoogleFonts.Face face = f.resolve("Archivo Black", GoogleFonts.FALLBACK_HEADING);
        assertThat(face.family()).isEqualTo("Archivo Black");
        assertThat(face.substituted()).isFalse();
        assertThat(face.specimenWeights()).containsExactly(400);
    }

    @Test
    void substitutesACommercialFace() {
        GoogleFonts f = fonts(Map.of("Fraunces", List.of(400, 700)));
        GoogleFonts.Face face = f.resolve("Söhne", GoogleFonts.FALLBACK_HEADING);
        assertThat(face.family()).isEqualTo("Fraunces");
        assertThat(face.substituted()).isTrue();
    }

    @Test
    void loadsTheNameUncheckedWhenGoogleIsUnreachable() {
        GoogleFonts f = new GoogleFonts(url -> -1);
        GoogleFonts.Face face = f.resolve("Space Grotesk", GoogleFonts.FALLBACK_BODY);
        assertThat(face.family()).isEqualTo("Space Grotesk");
        assertThat(face.weights()).containsExactly(400, 700);
        assertThat(face.substituted()).isFalse();
    }

    @Test
    void asksOnlyForTheWeightsEachFaceHas() {
        String href = GoogleFonts.stylesheetHref(
            new GoogleFonts.Face("Archivo Black", List.of(400), false, false),
            new GoogleFonts.Face("Source Sans 3", List.of(300, 400, 700), true, false));
        assertThat(href).isEqualTo(GoogleFonts.CSS2 + "?family=Archivo+Black:ital,wght@0,400"
            + "&amp;family=Source+Sans+3:ital,wght@0,300;0,400;0,700;1,400&amp;display=swap");
    }
}
