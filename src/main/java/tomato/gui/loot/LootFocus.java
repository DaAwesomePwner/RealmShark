package tomato.gui.loot;

import java.util.Objects;

/**
 * Route payload for {@code Destination.LOOT}: the Loot tab a route brings forward ({@link LootPage#tabTarget()}), the way
 * {@code RunsFocus} names a Runs & DPS tab. Plain, visit and query routes stay the Loot workspace's own targets (they bring
 * Explore forward through {@link LootPage#routes}); the tab target takes only a focus with no other reference. Immutable and
 * detached.
 * <p>{@code window} (Highlights only): the period Highlights opens on, applied and kept as a click on its Today / This session
 * choice is (Home's Notable loot tile passes the window it shows, P6b); null keeps the window Highlights shows.
 */
public record LootFocus(LootTab tab, HighlightsModel.Window window) {
    public LootFocus {
        Objects.requireNonNull(tab, "tab");
        if (window != null && tab != LootTab.HIGHLIGHTS) throw new IllegalArgumentException("A window applies to Loot highlights only");
    }

    /** The tab alone: Highlights keeps its window. */
    public LootFocus(LootTab tab) { this(tab, null); }
}
