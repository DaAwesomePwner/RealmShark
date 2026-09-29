package tomato.gui.loot;

/**
 * The tabs of Loot (the shell's {@code loot} page, spec §6.4), in their default order. {@link #id()} is the stable tab ID the
 * {@code CustomizableTabs("loot")} strip saves in {@code ui.tabs.loot}; {@link #title()} is the tab's label.
 */
public enum LootTab {
    /** What dropped Today or This session at a glance: tiles, notable drops and bags by dungeon ({@link LootHighlights}). */
    HIGHLIGHTS("highlights", "Highlights"),
    /** Every live and saved loot view behind one view selector: the Loot workspace. */
    EXPLORE("explore", "Explore");

    private final String id, title;

    LootTab(String id, String title) { this.id = id; this.title = title; }

    public String id() { return id; }
    public String title() { return title; }

    /** The tab with this ID, or null for null or an unknown ID (no tab selected, a foreign pane). */
    public static LootTab of(String id) {
        for (LootTab tab : values()) if (tab.id.equals(id)) return tab;
        return null;
    }
}
