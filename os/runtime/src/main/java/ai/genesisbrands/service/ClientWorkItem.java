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
