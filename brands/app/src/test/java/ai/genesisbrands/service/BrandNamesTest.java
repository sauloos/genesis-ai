package ai.genesisbrands.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrandNamesTest {

    @Test
    void dropsTheLegalSuffix() {
        assertThat(BrandNames.tradingName("One Way Coffee LTD")).isEqualTo("One Way Coffee");
        assertThat(BrandNames.tradingName("Hearth Bakery Limited")).isEqualTo("Hearth Bakery");
        assertThat(BrandNames.tradingName("Rudnick Transports, LLC")).isEqualTo("Rudnick Transports");
        assertThat(BrandNames.tradingName("Acme Inc.")).isEqualTo("Acme");
        assertThat(BrandNames.tradingName("Kontor GmbH")).isEqualTo("Kontor");
    }

    @Test
    void leavesOrdinaryNamesAlone() {
        assertThat(BrandNames.tradingName("Limited Edition Studio")).isEqualTo("Limited Edition Studio");
        assertThat(BrandNames.tradingName("Saga")).isEqualTo("Saga");
        assertThat(BrandNames.tradingName("Ltd")).isEqualTo("Ltd");
        assertThat(BrandNames.tradingName(null)).isNull();
        assertThat(BrandNames.tradingName("[business name not on record]"))
            .isEqualTo("[business name not on record]");
    }

    @Test
    void theNameBlockCarriesMeaningAndVersionsWithoutSuffixes() {
        String block = PurposePlaybookBriefDerivation.nameBlock(Map.of(
            "meaning", "Named after the ONE WAY street sign: there is a right way to do things.",
            "versions", List.of("One Way", "One Way Coffee Roasters", "One Way Coffee Ltd")));

        assertThat(block).contains("ONE WAY street sign")
            .contains("- One Way\n")
            .contains("- One Way Coffee Roasters\n")
            .contains("- One Way Coffee")
            .doesNotContain("Ltd");
        assertThat(PurposePlaybookBriefDerivation.nameBlock(null)).isEmpty();
    }
}
