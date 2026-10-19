package ai.genesisbrands.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UsagePricingTest {

    @Test
    void pricesByLongestPrefix() {
        UsagePricing pricing = new UsagePricing();
        // 1M in × $5 + 1M out × $25
        assertThat(pricing.costMicros("claude-opus-5-5", 1_000_000, 1_000_000, 0)).isEqualTo(30_000_000);
        assertThat(pricing.costMicros("claude-haiku-4-5-20251001", 1_000_000, 0, 0)).isEqualTo(1_000_000);
        assertThat(pricing.costMicros("gpt-image-1", 0, 0, 2)).isEqualTo(334_000);
    }

    @Test
    void configuredPricesOverrideDefaults() {
        UsagePricing pricing = new UsagePricing();
        UsagePricing.Price opus55 = new UsagePricing.Price();
        opus55.setInputPerMtok(new BigDecimal("7"));
        pricing.setPrices(Map.of("claude-opus-5-5", opus55, "claude-sonnet", opus55));
        assertThat(pricing.costMicros("claude-opus-5-5", 1_000_000, 0, 0)).isEqualTo(7_000_000);
        assertThat(pricing.costMicros("claude-opus-5", 1_000_000, 0, 0)).isEqualTo(5_000_000);
        assertThat(pricing.costMicros("claude-sonnet-5", 1_000_000, 0, 0)).as("a tie goes to configuration").isEqualTo(7_000_000);
    }

    @Test
    void unknownModelsCostNothing() {
        assertThat(new UsagePricing().costMicros("mystery-model", 1_000_000, 1_000_000, 1)).isZero();
        assertThat(new UsagePricing().costMicros(null, 1, 1, 0)).isZero();
    }
}
