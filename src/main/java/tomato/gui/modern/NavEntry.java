package tomato.gui.modern;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One shell destination. The ID is stable across renames and reordering: the shell, routes, saved preferences and tests
 * address pages by it. Titles are what the sidebar shows; {@code shortcut} is the Alt key that opens the page
 * ({@code KeyEvent.VK_*}, 0 for none).
 */
public record NavEntry(String id, String title, String description, int icon, Group group, int shortcut) {
    /** Where the sidebar shows a destination by default: every destination has a row and a compact-menu entry. */
    public enum Group { CORE, ADVANCED, SETTINGS }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /**
     * Every destination in the default sidebar order: core, then Advanced, then Settings. The Alt keys are the digits the
     * original pages had in their first order (Chat 1 … Loot 9, Logging 0) and letters for the later ones. Alt+5, Alt+7 and
     * Alt+8 belonged to the Statistics page and the Build and DPS Logger pointer pages, all removed in P6a; TomatoGUI binds them
     * to the Dungeons, Build and Live meter routes ({@link WorkspaceShell#bindShortcut}). Saved preferences may still name those
     * IDs; NavLayout ignores them.
     */
    private static final List<NavEntry> DEFAULTS = List.of(
        new NavEntry("home", "Home", "Your character, today's progress and recent runs at a glance.", LineIcon.HOME, Group.CORE, KeyEvent.VK_H),
        new NavEntry("characters", "Characters", "Keep a character roster, track maxing and equipment, and follow exalts.", 3, Group.CORE, KeyEvent.VK_4),
        // Runs & DPS (P5b): the run feed, Dungeons, the live meter and the recordings, as tabs of one page.
        new NavEntry("runs", "Runs & DPS", "Review runs, dungeons, live damage and recordings.", LineIcon.SWORDS, Group.CORE, KeyEvent.VK_R),
        new NavEntry("loot", "Loot", "Notable drops at a glance, and every loot view live or saved.", 9, Group.CORE, KeyEvent.VK_9),
        new NavEntry("quests", "Quests", "Review quests collected from the Daily Quest Room.", 5, Group.CORE, KeyEvent.VK_6),
        new NavEntry("chat", "Chat", "Your conversations across the Realm, in one place.", 0, Group.CORE, KeyEvent.VK_1),
        new NavEntry("party", "Party", "Inspect current players, saved run loadouts and ability activity.", 2, Group.ADVANCED, KeyEvent.VK_3),
        new NavEntry("key-pops", "Key-pops", "Follow dungeon openings and configure your notifications.", 1, Group.ADVANCED, KeyEvent.VK_2),
        new NavEntry("timeline", "Timeline", "Follow party, equipment and progression events across your sessions.", 12, Group.ADVANCED, KeyEvent.VK_T),
        new NavEntry("logging", "Logging", "Discover available fields, inspect stat changes and diagnose capture gaps.", 10, Group.ADVANCED, KeyEvent.VK_0),
        new NavEntry("bridge-review", "Bridge Review", "Review detected loot, configure guild exports and troubleshoot delivery.", 13, Group.ADVANCED, KeyEvent.VK_B),
        new NavEntry("settings", "Settings", "Notifications, general, appearance, loot filters, chat and about.", LineIcon.GEAR, Group.SETTINGS, KeyEvent.VK_N));

    public NavEntry {
        if (id == null || !ID.matcher(id).matches())
            throw new IllegalArgumentException("Navigation IDs use lowercase letters, digits and hyphens: " + id);
        // The shell names rows "nav-<id>" beside its own "nav-advanced" and "nav-menu…" components.
        if (id.equals("advanced") || id.equals("menu"))
            throw new IllegalArgumentException("Navigation ID is reserved by the shell: " + id);
        if (shortcut < 0) throw new IllegalArgumentException("Shortcut key codes are not negative: " + shortcut);
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(group, "group");
    }

    public static List<NavEntry> defaults() { return DEFAULTS; }

    /** The destination with this ID, or null for IDs this version does not know. */
    public static NavEntry forId(String id) {
        for (NavEntry entry : DEFAULTS) if (entry.id().equals(id)) return entry;
        return null;
    }
}
