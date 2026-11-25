package ai.genesisbrands.agent.logo;

import ai.genesisbrands.service.BlobStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages the logo variants for a creative direction into a ZIP archive and
 * uploads it to blob storage for client download. SVG logos get all three
 * recoloured variants (primary, whiteout, mono); image logos include the primary lockup
 * and, when there is one, the symbol cut from it (pixel-level recolouring requires a graphics
 * library not in scope).
 */
@Service
public class LogoExportService {

    private static final Logger log = LoggerFactory.getLogger(LogoExportService.class);

    private static final String ASSET_URL_PREFIX = "/api/assets/";

    private final BlobStorageService blobStorageService;
    private final LogoVariantService logoVariantService;

    public LogoExportService(BlobStorageService blobStorageService,
                              LogoVariantService logoVariantService) {
        this.blobStorageService = blobStorageService;
        this.logoVariantService = logoVariantService;
    }

    /**
     * Builds the logo ZIP and uploads it to blob storage.
     *
     * @param generationId unique per-generation suffix (e.g. a regeneration job id) so each
     *                      new ZIP gets its own blob path instead of overwriting the last one —
     *                      otherwise an unapproved draft's logo would clobber the current one.
     * @return blobPath of the stored ZIP, or null if packaging failed (non-fatal)
     */
    public String packageLogos(String engagementId, String direction,
                                LogoOutput logo, String primaryHex, String generationId) {
        String dir = direction.toLowerCase();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                if (logo.method() == LogoOutput.Method.SVG_CONCEPT && logo.svgMarkup() != null) {
                    String svg = cleanSvg(logo.svgMarkup());
                    addEntry(zos, dir + "-logo-primary.svg",   svg.getBytes(StandardCharsets.UTF_8));
                    addEntry(zos, dir + "-logo-whiteout.svg",  logoVariantService.whiteout(svg).getBytes(StandardCharsets.UTF_8));
                    addEntry(zos, dir + "-logo-mono.svg",      logoVariantService.mono(svg, primaryHex).getBytes(StandardCharsets.UTF_8));
                } else if (logo.isImage() && logo.imageUrl() != null) {
                    byte[] png = download(logo.imageUrl());
                    if (png != null) {
                        addEntry(zos, dir + "-logo-primary.png", png);
                    }
                    if (logo.symbolImageUrl() != null) {
                        byte[] symbol = download(logo.symbolImageUrl());
                        if (symbol != null) {
                            addEntry(zos, dir + "-logo-symbol.png", symbol);
                        }
                    }
                }
                addEntry(zos, "README.txt",
                    buildReadme(logo, direction, primaryHex).getBytes(StandardCharsets.UTF_8));
            }

            String blobPath = "assets/logos/%s/%s-%s-logo-package.zip"
                .formatted(engagementId, dir, generationId);
            blobStorageService.upload(blobPath, baos.toByteArray());
            log.info("Logo package stored at {} for engagement {}", blobPath, engagementId);
            return blobPath;

        } catch (Exception e) {
            log.warn("Logo export failed for {} direction of engagement {} (non-fatal): {}",
                direction, engagementId, e.getMessage());
            return null;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] download(String assetUrl) {
        String blobPath = assetUrl.startsWith(ASSET_URL_PREFIX)
            ? assetUrl.substring(ASSET_URL_PREFIX.length())
            : assetUrl;
        try {
            return blobStorageService.download(blobPath);
        } catch (Exception e) {
            log.warn("Logo export: cannot read {} (skipped): {}", blobPath, e.getMessage());
            return null;
        }
    }

    private static void addEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static String cleanSvg(String svg) {
        svg = svg.strip();
        if (svg.startsWith("<?xml")) {
            int end = svg.indexOf("?>");
            if (end >= 0) svg = svg.substring(end + 2).strip();
        }
        return svg;
    }

    private static String buildReadme(LogoOutput logo, String direction, String primaryHex) {
        LogoOutput.Method method = logo.method();
        String label = direction.substring(0, 1).toUpperCase()
            + direction.substring(1).toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append("Genesis AI — Brand Logo Package\n");
        sb.append("================================\n");
        sb.append("Direction: ").append(label).append("\n");
        sb.append("Primary colour: ").append(primaryHex).append("\n\n");
        if (method == LogoOutput.Method.SVG_CONCEPT) {
            sb.append("Files included\n--------------\n");
            sb.append("  *-logo-primary.svg   Full-colour logo for digital use\n");
            sb.append("  *-logo-whiteout.svg  All-white version for use on coloured backgrounds\n");
            sb.append("  *-logo-mono.svg      Single-colour version for print / embossing\n\n");
            sb.append("SVG files are vector-based and resolution-independent.\n");
            sb.append("Open with Figma, Illustrator, or Inkscape to export at any size.\n");
        } else {
            sb.append("Files included\n--------------\n");
            sb.append("  *-logo-primary.png   Primary lockup (symbol + wordmark), transparent PNG\n");
            if (logo.symbolImageUrl() != null) {
                sb.append("  *-logo-symbol.png    The symbol alone, for app icons, favicons and avatars\n");
            }
            sb.append("\nThese files are high-resolution raster images. For large-format print we\n");
            sb.append("recommend having the final logo redrawn in vector by a graphic designer.\n");
        }
        sb.append("\nGenerated by Genesis AI — genesisbrands.ai\n");
        return sb.toString();
    }
}
