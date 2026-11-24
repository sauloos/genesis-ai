package ai.genesisbrands.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Which OpenAI image model every agent that generates pictures calls, as configuration
 * rather than a constant.
 *
 * <p>This exists because the alternative failed in production: {@code gpt-image-1} was
 * hardcoded in three agents, OpenAI retired it on 2026-10-23, and from that day every
 * engagement died at the Logo agent with "The model 'gpt-image-1' does not exist" — the
 * Logo agent's failure is fatal to the run. A provider retiring a model is a fact about
 * the world, not a code change, so the id lives in {@code genesis.images.model} where an
 * operator can move it with an environment variable and a restart.
 *
 * <p>The default is the current generation. {@code quality} is passed per call site (the
 * Logo agent asks for high, the Content agent for medium), and every 2.x image model
 * returns {@code b64_json} and takes {@code size} as a string, which is what the call
 * sites assume. Whatever is set here must also be priced in
 * {@code UsagePricing} — an unpriced model meters as zero.
 */
@Component
@ConfigurationProperties(prefix = "genesis.images")
public class ImageModelProperties {

    /**
     * Sunburst rather than flare: the two cost the same, flare is faster and sunburst is
     * the stronger of the pair, and nothing here is latency-sensitive — a logo is generated
     * inside a multi-minute engagement, a social image inside an unattended standing run.
     */
    private String model = "gpt-image-2.6-sunburst";

    public String getModel() { return model; }

    public void setModel(String model) { this.model = model; }
}
