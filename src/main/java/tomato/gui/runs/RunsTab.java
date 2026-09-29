package tomato.gui.runs;

/**
 * The tabs of Runs & DPS (the shell's {@code runs} page, spec §6.3), in their default order. {@link #id()} is the stable tab ID the
 * {@code CustomizableTabs("runs")} strip saves in {@code ui.tabs.runs}; {@link #title()} is the tab's label.
 */
public enum RunsTab {
    /** The run feed (cards, the Runs archive as its Table view, and one run's recap): the {@link RunsPage}. */
    FEED("feed", "Feed"),
    /** One card per dungeon from exact run, loot and combat links. */
    DUNGEONS("dungeons", "Dungeons"),
    /** The single DPS meter ({@code DpsGUI}) with its nested Meters and Resources & buffs tabs. */
    LIVE_METER("live-meter", "Live meter"),
    /** Every combat recording: this app run, saved summaries, kept full detail and imports. */
    RECORDINGS("recordings", "Recordings");

    private final String id, title;

    RunsTab(String id, String title) { this.id = id; this.title = title; }

    public String id() { return id; }
    public String title() { return title; }

    /** The tab with this ID, or null for null or an unknown ID (no tab selected, a foreign pane). */
    public static RunsTab of(String id) {
        for (RunsTab tab : values()) if (tab.id.equals(id)) return tab;
        return null;
    }
}
