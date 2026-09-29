package tomato.gui.modern;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One shell destination. The ID is stable across renames and reordering; the page index is what the
 * shell, routes and tests use until P6 replaces it with the ID. Titles are what the sidebar shows.
 */
public record NavEntry(String id, int page, String title, String description, int icon, Group group) {
    /**
     * Where the sidebar shows a destination by default. UNLISTED pages keep a page, a route and a shortcut
     * but have no sidebar row and no compact-menu entry.
     */
    public enum Group { CORE, ADVANCED, SETTINGS, UNLISTED }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** Every destination in the default sidebar order: core, then Advanced, then Settings, then unlisted pages. */
    private static final List<NavEntry> DEFAULTS = List.of(
        new NavEntry("home", 14, "Home", "Your character, today's progress and recent runs at a glance.", LineIcon.HOME, Group.CORE),
        new NavEntry("characters", 3, "Characters", "Keep a character roster, track maxing and equipment, and follow exalts.", 3, Group.CORE),
        // Runs & DPS (P5b): the run feed, Dungeons, the live meter and the recordings, as tabs of one page.
        new NavEntry("runs", 10, "Runs & DPS", "Review runs, dungeons, live damage and recordings.", LineIcon.SWORDS, Group.CORE),
        new NavEntry("loot", 8, "Loot", "Explore live loot by item, stat potion and bag type.", 9, Group.CORE),
        new NavEntry("quests", 5, "Quests", "Review quests collected from the Daily Quest Room.", 5, Group.CORE),
        new NavEntry("chat", 0, "Chat", "Your conversations across the Realm, in one place.", 0, Group.CORE),
        new NavEntry("party", 2, "Party", "Inspect current players, saved run loadouts and ability activity.", 2, Group.ADVANCED),
        new NavEntry("key-pops", 1, "Key-pops", "Follow dungeon openings and configure your notifications.", 1, Group.ADVANCED),
        new NavEntry("timeline", 11, "Timeline", "Follow party, equipment and progression events across your sessions.", 12, Group.ADVANCED),
        new NavEntry("logging", 9, "Logging", "Discover available fields, inspect stat changes and diagnose capture gaps.", 10, Group.ADVANCED),
        new NavEntry("bridge-review", 12, "Bridge Review", "Review detected loot, configure guild exports and troubleshoot delivery.", 13, Group.ADVANCED),
        new NavEntry("settings", 13, "Settings", "Notifications, sounds and appearance.", LineIcon.GEAR, Group.SETTINGS),
        // Build was My Info; its ID stays so saved preferences still recognise (and ignore) it.
        new NavEntry("my-info", 6, "Build", "Build moved to the character sheet: open a character and choose Build.", LineIcon.INFO, Group.UNLISTED),
        // P5b: the live meter and the recordings moved into Runs & DPS; page 7 only points there (Alt+8 opens the Live meter tab).
        new NavEntry("dps-logger", 7, "DPS Logger", "The live meter and your recordings are now tabs of Runs & DPS.", 7, Group.UNLISTED),
        // P5b: Statistics left the sidebar until P6 retires it; Alt+5, Settings search and the Dungeons analysis banner reach it.
        new NavEntry("statistics", 4, "Statistics", "Track fame, loot and dungeon progress over time.", 4, Group.UNLISTED));

    public NavEntry {
        if (id == null || !ID.matcher(id).matches())
            throw new IllegalArgumentException("Navigation IDs use lowercase letters, digits and hyphens: " + id);
        if (page < 0) throw new IllegalArgumentException("Page indices are not negative: " + page);
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(group, "group");
    }

    public static List<NavEntry> defaults() { return DEFAULTS; }

    public static NavEntry forPage(int page) {
        for (NavEntry entry : DEFAULTS) if (entry.page() == page) return entry;
        throw new IllegalArgumentException("No destination for page " + page);
    }

    /** The destination with this ID, or null for IDs this version does not know. */
    public static NavEntry forId(String id) {
        for (NavEntry entry : DEFAULTS) if (entry.id().equals(id)) return entry;
        return null;
    }

    /** Titles indexed by page, for {@link WorkspaceShell#TITLES}. */
    public static String[] titles() {
        String[] titles = new String[DEFAULTS.size()];
        for (NavEntry entry : DEFAULTS) titles[entry.page()] = entry.title();
        return titles;
    }
}
