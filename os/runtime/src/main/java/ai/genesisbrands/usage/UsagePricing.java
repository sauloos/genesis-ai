package ai.genesisbrands.usage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider list prices, in US dollars. Built-in defaults cover the models this platform ships
 * with; {@code genesis.usage.prices.<model-prefix>.*} overrides or adds to them. A model is
 * priced by its longest matching prefix, so dated ids ({@code claude-haiku-4-5-20251001})
 * resolve to their family. An unknown model costs 0 and is logged once.
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "genesis.usage")
public class UsagePricing {

    public static class Price {
        /** Dollars per million input tokens. */
        private BigDecimal inputPerMtok = BigDecimal.ZERO;
        /** Dollars per million output tokens. */
        private BigDecimal outputPerMtok = BigDecimal.ZERO;
        /** Dollars per generated image. */
        private BigDecimal perImage = BigDecimal.ZERO;

        public Price() {}

        Price(String in, String out, String image) {
            this.inputPerMtok = new BigDecimal(in);
            this.outputPerMtok = new BigDecimal(out);
            this.perImage = new BigDecimal(image);
        }

        public BigDecimal getInputPerMtok() { return inputPerMtok; }
        public void setInputPerMtok(BigDecimal v) { this.inputPerMtok = v; }
        public BigDecimal getOutputPerMtok() { return outputPerMtok; }
        public void setOutputPerMtok(BigDecimal v) { this.outputPerMtok = v; }
        public BigDecimal getPerImage() { return perImage; }
        public void setPerImage(BigDecimal v) { this.perImage = v; }
    }

    private static final Map<String, Price> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("claude-fable", new Price("10", "50", "0"));
        DEFAULTS.put("claude-opus", new Price("5", "25", "0"));
        DEFAULTS.put("claude-sonnet", new Price("3", "15", "0"));
        DEFAULTS.put("claude-haiku", new Price("1", "5", "0"));
        // gpt-image-1, 1024x1024 at high quality, the setting the Logo agent uses.
        DEFAULTS.put("gpt-image-1", new Price("0", "0", "0.167"));
        // gpt-image-1, 1024x1024 at medium quality, the setting the Content agent uses.
        DEFAULTS.put("gpt-image-1-medium", new Price("0", "0", "0.042"));
        DEFAULTS.put("ideogram-v2", new Price("0", "0", "0.08"));
        DEFAULTS.put("text-embedding-3-small", new Price("0.02", "0", "0"));
        DEFAULTS.put("text-embedding-3-large", new Price("0.13", "0", "0"));
    }

    private Map<String, Price> prices = new HashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public Map<String, Price> getPrices() { return prices; }
    public void setPrices(Map<String, Price> prices) { this.prices = prices; }

    public Price priceFor(String model) {
        if (model == null || model.isBlank()) return null;
        Price best = null;
        int bestLen = -1;
        for (Map<String, Price> table : java.util.List.of(DEFAULTS, prices)) {
            for (Map.Entry<String, Price> e : table.entrySet()) {
                // Configured entries win ties with defaults because they're checked second.
                if (model.startsWith(e.getKey()) && e.getKey().length() >= bestLen) {
                    best = e.getValue();
                    bestLen = e.getKey().length();
                }
            }
        }
        if (best == null && warned.add(model)) {
            log.warn("No price for model '{}' — its usage is recorded at cost 0. Set genesis.usage.prices.{}.*", model, model);
        }
        return best;
    }

    /** Cost in millionths of a dollar. */
    public long costMicros(String model, long inputTokens, long outputTokens, int images) {
        Price p = priceFor(model);
        if (p == null) return 0;
        // $/Mtok × tokens = micro-dollars exactly, so token costs need no scaling.
        BigDecimal micros = p.inputPerMtok.multiply(BigDecimal.valueOf(inputTokens))
            .add(p.outputPerMtok.multiply(BigDecimal.valueOf(outputTokens)))
            .add(p.perImage.multiply(BigDecimal.valueOf(images)).multiply(BigDecimal.valueOf(1_000_000)));
        return micros.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
