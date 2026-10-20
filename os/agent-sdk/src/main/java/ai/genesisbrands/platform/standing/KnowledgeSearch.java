package ai.genesisbrands.platform.standing;

import java.util.List;

/**
 * Search over the platform's knowledge base by meaning: Layer 1 (the agency's reasoning
 * substrate) and Layer 2 (project memory, every completed engagement). The runtime provides the
 * one implementation; an agent asks for it optionally and works without it.
 */
public interface KnowledgeSearch {

    String LAYER1 = "layer1";
    String LAYER2 = "layer2";

    /** Up to {@code topK} passages closest to {@code query}, from the given layers, best first. */
    List<Passage> search(String query, int topK, List<String> layers);

    /** @param score similarity, 0–1 */
    record Passage(String title, String text, String sourceUrl, String layer, double score) {}
}
