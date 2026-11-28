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
    /**
     * @param decision what a client needs to compare variants and pick one, or null when the
     *                 tenant offers no choice for this item
     */
    public record Variant(
        String key,
        boolean chosen,
        List<Asset> assets,
        List<VersionRef> versions,
        Decision decision
    ) {
        public Variant(String key, boolean chosen, List<Asset> assets, List<VersionRef> versions) {
            this(key, chosen, assets, versions, null);
        }
    }

    /**
     * The comparison card for one variant, consumed by the core {@code variantChooser} widget.
     * Every field is optional; the widget renders what is present. Tenant wording only, so the
     * platform never decides what a "direction" means.
     *
     * @param descriptor   one plain-words line on what makes this variant different
     * @param headlineFont a plain font-family name the tagline is set in (widget validates it)
     * @param palette      up to five {@code #rrggbb} colours
     * @param keeps        what this variant keeps from today
     * @param letsGo       what it deliberately lets go of
     * @param previewUrl   cover image, a plain GET the client's session authorises
     */
    public record Decision(
        String label,
        String descriptor,
        String tagline,
        String headlineFont,
        String summary,
        String personality,
        List<String> palette,
        List<String> keeps,
        List<String> letsGo,
        String bet,
        String risk,
        String previewUrl
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
