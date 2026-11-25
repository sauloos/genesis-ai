package ai.genesisbrands.agent.logo;

import ai.genesisbrands.service.BlobStorageService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Puts a logo that was not drawn by this pipeline run into a direction: the client's own logo
 * when they chose to keep it, or a logo an admin brings in. Stores the files next to the
 * engagement's generated logos and cuts the symbol from the lockup by its pixels, exactly as a
 * generated logo's symbol is cut, so the brand book and logo package treat it like any other.
 */
@Service
public class AdoptedLogos {

    private final BlobStorageService blobStorage;

    public AdoptedLogos(BlobStorageService blobStorage) {
        this.blobStorage = blobStorage;
    }

    /**
     * The client's own logo as a direction's logo, described by its assessment.
     *
     * @param image a PNG; the symbol is cut from it when its background is transparent
     */
    public LogoOutput clientLogo(String engagementId, String direction, byte[] image, LogoAssessment assessment) {
        String description = assessment == null ? "The client's own logo, kept as it is."
            : "The client's own logo, kept as it is. " + assessment.description();
        String symbolism = assessment == null ? null : assessment.reading();
        String rationale = assessment == null
            ? "The client chose to keep their current logo."
            : "The client chose to keep their current logo. Our assessment: " + assessment.score()
                + "/100, recommendation " + assessment.verdict() + ". " + assessment.headline();
        LogoOutput base = new LogoOutput(engagementId, LogoOutput.Method.CLIENT, description, symbolism,
            null, null, null, rationale, 1, null,
            assessment == null ? null : "The symbol cut from the client's own logo.", null, rationale);
        return adopt(base, engagementId, direction, image, null);
    }

    /**
     * Stores {@code primary} (and {@code symbol}, or one cut from the primary) as this direction's
     * logo files and returns {@code logo} pointing at them. Candidate images from elsewhere are
     * dropped because their files do not travel with it.
     */
    public LogoOutput adopt(LogoOutput logo, String engagementId, String direction, byte[] primary, byte[] symbol) {
        if (primary == null || primary.length == 0) throw new IllegalArgumentException("No logo image");
        String stem = "assets/logos/%s/%s-adopted-%d".formatted(engagementId,
            direction.toLowerCase(Locale.ROOT), System.currentTimeMillis());
        String primaryPath = stem + ".png";
        store(primaryPath, primary);

        byte[] cut = symbol != null && symbol.length > 0 ? symbol : SymbolCrop.crop(primary).orElse(null);
        String symbolUrl = null;
        if (cut != null) {
            String symbolPath = stem + "-symbol.png";
            store(symbolPath, cut);
            symbolUrl = "/api/assets/" + symbolPath;
        }
        return new LogoOutput(engagementId, logo.method(), logo.conceptDescription(), logo.symbolism(),
            logo.imagePrompt(), "/api/assets/" + primaryPath, logo.svgMarkup(), logo.reasoning(),
            logo.iteration(), symbolUrl, symbolUrl == null ? null : logo.symbolDescription(), null,
            logo.selectionRationale());
    }

    /** The symbol cut from a lockup, when its layout allows a clean cut. */
    public static Optional<byte[]> cutSymbol(byte[] png) {
        return SymbolCrop.crop(png);
    }

    private void store(String path, byte[] bytes) {
        try {
            blobStorage.upload(path, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store logo image: " + e.getMessage(), e);
        }
    }
}
