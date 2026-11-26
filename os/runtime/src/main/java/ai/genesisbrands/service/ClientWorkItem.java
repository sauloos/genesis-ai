package ai.genesisbrands.service;

import java.time.Instant;
import java.util.List;

/**
 * Generic shape for one client-owned deliverable, consumed by the core
 * {@code dashboardAssets}/{@code dashboardAgents} widgets. Mirrors the
 * ConsultantSubject split: the platform defines this shape, {@link ClientWorkspaceProvider}'s
 * tenant implementation decides what's inside it (today, an Engagement's DirectionOutputs).
 *
 * @param inProgress the item is still being produced; the dashboard re-checks until it isn't
 * @param statusNote a client-facing line about the item's state (e.g. why it has no assets
 *                   yet), or null
 */
public record ClientWorkItem(
    String id,
    String title,
    boolean unlocked,
    List<Variant> variants,
    boolean inProgress,
    String statusNote
) {
    public record Variant(
        String key,
        boolean chosen,
        List<Asset> assets,
        List<VersionRef> versions
    ) {}

    /**
     * @param downloadUrl the dashboard downloads by navigating to it, so it must be a plain
     *     GET that the client's session cookie authorises (no X-Api-Key or other custom
     *     header; exempt it with an {@code ApiKeyExemption}) and that answers with
     *     {@code Content-Disposition: attachment}.
     */
    public record Asset(
        String kind,
        String label,
        boolean ready,
        String previewUrl,
        String downloadUrl,
        String inlineSvg
    ) {}

    public record VersionRef(int versionNumber, Instant createdAt) {}
}
