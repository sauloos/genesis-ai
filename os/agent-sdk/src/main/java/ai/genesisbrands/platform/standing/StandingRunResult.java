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
     * @param kind    {@link #REPORT}, {@link #DRAFT_POST}, {@link #ASSET} or {@link #PROPOSAL}
     * @param body    markdown shown in the inbox
     * @param payload structured data for the item's kind (e.g. per-channel post variants); may be empty
     */
    public record Item(String kind, String title, String summary, String body, Map<String, Object> payload) {

        public static final String REPORT = "REPORT";
        public static final String DRAFT_POST = "DRAFT_POST";
        public static final String ASSET = "ASSET";
        public static final String PROPOSAL = "PROPOSAL";

        public static Item report(String title, String summary, String body) {
            return new Item(REPORT, title, summary, body, Map.of());
        }
    }
}
