package ai.genesisbrands.service;

import java.time.Instant;
import java.util.List;

/**
 * Generic shape for one client-owned deliverable, consumed by the core
 * {@code dashboardAssets}/{@code dashboardAgents} widgets. Mirrors the
 * ConsultantSubject split: the platform defines this shape, {@link ClientWorkspaceProvider}'s
 * tenant implementation decides what's inside it (today, an Engagement's DirectionOutputs).
 */
public record ClientWorkItem(
    String id,
    String title,
    boolean unlocked,
    List<Variant> variants
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
