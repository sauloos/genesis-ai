package ai.genesisbrands.platform.standing;

import java.util.List;
import java.util.Map;

/**
 * What a standing run produced: items for the client's inbox, never a side effect.
 *
 * @param note a one-line summary for the run log (admin and client run history)
 */
public record StandingRunResult(List<Item> items, String note) {

    public static StandingRunResult of(Item... items) {
        return new StandingRunResult(List.of(items), null);
    }

    /**
     * One inbox item.
     *
     * <p>A {@link #DRAFT_POST} payload follows one shape, so every inbox renders and exports it
     * the same way whichever agent wrote it:
     * <pre>
     * { "variants": [ { "channel": "instagram", "label": "Instagram", "text": "...", "hashtags": ["..."] } ],
     *   "format": "Carousel", "pillar": "Education", "proposedFor": "2026-10-22" }
     * </pre>
     * The images of a post are its {@code attachments}, in display order.
     *
     * <p>A {@link #REPORT} that scores something may add a {@code scorecard}, which every inbox
     * renders as scores and a ranked list of fixes (the plain {@code body} stays the fallback):
     * <pre>
     * { "scorecard": { "score": 78,
     *     "dimensions": [ { "name": "Colours", "score": 64, "note": "..." } ],
     *     "findings": [ { "severity": "high|medium|low", "area": "...", "where": "...",
     *                     "issue": "...", "evidence": "...", "fix": "..." } ],
     *     "highlights": ["..."], "quickFixes": ["..."],
     *     "sources": [ { "label": "Website", "detail": "https://...", "ok": true, "result": "..." } ] } }
     * </pre>
     *
     * @param kind        {@link #REPORT}, {@link #DRAFT_POST}, {@link #ASSET} or {@link #PROPOSAL}
     * @param body        markdown shown in the inbox
     * @param payload     structured data for the item's kind (e.g. per-channel post variants); may be empty
     * @param attachments files the runtime stores with the item (images, documents); the client
     *                    can view and download them; may be empty
     */
    public record Item(String kind, String title, String summary, String body, Map<String, Object> payload,
                       List<Attachment> attachments) {

        public static final String REPORT = "REPORT";
        public static final String DRAFT_POST = "DRAFT_POST";
        public static final String ASSET = "ASSET";
        public static final String PROPOSAL = "PROPOSAL";

        public Item(String kind, String title, String summary, String body, Map<String, Object> payload) {
            this(kind, title, summary, body, payload, List.of());
        }

        public static Item report(String title, String summary, String body) {
            return new Item(REPORT, title, summary, body, Map.of());
        }
    }

    /** A file produced by a run. {@code name} is a file name such as {@code frame-1.png}. */
    public record Attachment(String name, String contentType, byte[] data) {}
}
