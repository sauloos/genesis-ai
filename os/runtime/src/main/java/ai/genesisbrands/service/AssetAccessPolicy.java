package ai.genesisbrands.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Port consulted by {@code AssetController} before it streams a blob over {@code /api/assets/**}.
 * Core knows nothing about what a blob is; a tenant implements this to withhold the blobs that
 * are paid deliverables (brand-book PDFs, logo packages). Every registered policy must allow a
 * path for it to be served; with no policy registered everything is served as before.
 */
public interface AssetAccessPolicy {

    /** @param blobPath storage path without the {@code /api/assets/} prefix, already normalised */
    boolean allows(String blobPath, HttpServletRequest request);
}
