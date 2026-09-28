package tomato.gui.runs;

import java.util.Objects;

/**
 * Route payload for {@code Destination.RUNS}: the Runs & DPS tab a route brings forward ({@link RunsDpsPage#tabTarget()}), the
 * way {@code QuestsFocus} names a Quests tab. {@code dungeon} is a canonical dungeon name for the Feed's dungeon filter (the
 * Dungeons tab's "Show runs"), else null; a blank name is none, and only a Feed route carries one. A plain {@code RUNS} route
 * (no payload) stays the feed target's. Immutable and detached.
 */
public record RunsFocus(RunsTab tab, String dungeon) {
    public RunsFocus {
        Objects.requireNonNull(tab, "tab");
        if (dungeon != null && dungeon.isBlank()) dungeon = null;
        if (dungeon != null && tab != RunsTab.FEED) throw new IllegalArgumentException("Only the Feed filters by dungeon: " + tab);
    }

    /** The tab alone, no dungeon filter. */
    public static RunsFocus of(RunsTab tab) { return new RunsFocus(tab, null); }
}
