package ai.genesisbrands.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Lives with the brand book, whose colour pages print these references. */
class PantoneMatchTest {

    @Test
    void deepGreensMatchAGreenNotBlack() {
        for (String hex : new String[]{"#1F3A27", "#2F4A3B"}) {
            assertThat(ColorSpecificationService.nearestPantoneStatic(hex)).as(hex).doesNotContain("Black");
        }
        // Black 3 C is Pantone's green-black, the honest match for a near-black pine.
        assertThat(ColorSpecificationService.nearestPantoneStatic("#14211A")).isIn("Pantone Black 3 C", "Pantone 5535 C");
    }

    @Test
    void trueBlacksStillMatchBlack() {
        assertThat(ColorSpecificationService.nearestPantoneStatic("#2C2A29")).isEqualTo("Pantone Black C");
    }
}
