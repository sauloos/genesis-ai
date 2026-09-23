package ai.genesisbrands.platform;

import java.util.List;

/**
 * A pre-built, code-level page layout the builder's layout picker offers — a fixed
 * set of named slots arranged via a CSS grid-template-columns hint. Not persisted or
 * tenant-extensible; new layouts are added here as the set of real needs grows.
 */
public record PageLayout(
    String id,
    String label,
    List<Slot> slots,
    String gridTemplateColumns
) {

    public record Slot(String key, String label) {}

    public static final List<PageLayout> ALL = List.of(
        new PageLayout(
            "SINGLE_COLUMN", "Single column",
            List.of(new Slot("main", "Main")),
            "1fr"
        ),
        new PageLayout(
            "TWO_COLUMN", "Two column",
            List.of(new Slot("left", "Left"), new Slot("right", "Right")),
            "1fr 1fr"
        ),
        new PageLayout(
            "HERO_PLUS_BODY", "Hero + body",
            List.of(new Slot("hero", "Hero"), new Slot("body", "Body")),
            "1fr"
        ),
        new PageLayout(
            "SIDEBAR_LEFT", "Sidebar + main",
            List.of(new Slot("sidebar", "Sidebar"), new Slot("main", "Main")),
            "280px 1fr"
        )
    );

    public static PageLayout byId(String id) {
        return ALL.stream().filter(l -> l.id().equals(id)).findFirst().orElse(null);
    }
}
