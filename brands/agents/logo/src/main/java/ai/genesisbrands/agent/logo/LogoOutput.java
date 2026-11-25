package ai.genesisbrands.agent.logo;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * One direction's logo. For the image methods (DALLE, IDEOGRAM) it is a small logo system:
 * {@code imageUrl} is the primary lockup (symbol + wordmark), the one shown and placed everywhere,
 * and {@code symbolImageUrl} is the symbol on its own, cut from that lockup so the two always
 * match (favicon, app icon, avatar). {@code candidateImageUrls} are the lockups the creative
 * director chose between, and {@code selectionRationale} is why the chosen one won.
 *
 * <p>The fields after {@code iteration} were added later, so results stored before them read
 * back with those fields null.
 */
public record LogoOutput(
    String engagementId,
    Method method,
    String conceptDescription,
    String symbolism,
    String imagePrompt,   // image methods — the lockup prompt
    String imageUrl,      // image methods — the primary lockup, a servable asset URL
    String svgMarkup,     // SVG_CONCEPT method only
    String reasoning,
    int iteration,
    String symbolImageUrl,
    String symbolDescription,
    List<String> candidateImageUrls,
    String selectionRationale
) {
    /** CLIENT is the client's own logo, adopted as it is (they chose to keep it). */
    public enum Method { DALLE, SVG_CONCEPT, IDEOGRAM, CLIENT }

    public LogoOutput(String engagementId, Method method, String conceptDescription, String symbolism,
                      String imagePrompt, String imageUrl, String svgMarkup, String reasoning, int iteration) {
        this(engagementId, method, conceptDescription, symbolism, imagePrompt, imageUrl, svgMarkup,
            reasoning, iteration, null, null, null, null);
    }

    /** True for the methods that produce a raster image rather than SVG markup. */
    @JsonIgnore
    public boolean isImage() {
        return method == Method.DALLE || method == Method.IDEOGRAM || method == Method.CLIENT;
    }
}
