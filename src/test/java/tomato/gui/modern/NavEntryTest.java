package tomato.gui.modern;

import java.awt.event.KeyEvent;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Destination IDs carry each page's Alt key; the IDs the shell's own component names use are reserved. */
public class NavEntryTest {
    /**
     * The Alt keys pages had by number (Alt+1 … Alt+9, Alt+0, then letters), by the fixed page → ID table. P6a removed the Build
     * (my-info, Alt+7) and DPS Logger (dps-logger, Alt+8) pointer pages and the Statistics page (statistics, Alt+5); TomatoGUI
     * binds those three keys to routes instead.
     */
    private static final Map<String, Integer> OLD_KEYS = new LinkedHashMap<>();
    static {
        OLD_KEYS.put("chat", KeyEvent.VK_1); OLD_KEYS.put("key-pops", KeyEvent.VK_2); OLD_KEYS.put("party", KeyEvent.VK_3);
        OLD_KEYS.put("characters", KeyEvent.VK_4); OLD_KEYS.put("quests", KeyEvent.VK_6);
        OLD_KEYS.put("loot", KeyEvent.VK_9);
        OLD_KEYS.put("logging", KeyEvent.VK_0); OLD_KEYS.put("runs", KeyEvent.VK_R); OLD_KEYS.put("timeline", KeyEvent.VK_T);
        OLD_KEYS.put("bridge-review", KeyEvent.VK_B); OLD_KEYS.put("settings", KeyEvent.VK_N); OLD_KEYS.put("home", KeyEvent.VK_H);
    }

    @Test public void everyDestinationKeepsTheAltKeyItHadByPageNumber() {
        Map<String, Integer> keys = new LinkedHashMap<>();
        for (NavEntry entry : NavEntry.defaults()) assertNull("Unique ID " + entry.id(), keys.put(entry.id(), entry.shortcut()));
        assertEquals("Every ID keeps its key and no ID is added or lost", new TreeMap<>(OLD_KEYS), new TreeMap<>(keys));
        assertEquals("No two destinations share a key", keys.size(), new HashSet<>(keys.values()).size());
        for (int free : new int[] {KeyEvent.VK_5, KeyEvent.VK_7, KeyEvent.VK_8})
            assertFalse("Alt+5, Alt+7 and Alt+8 belong to no page; TomatoGUI binds them to Dungeons, Build and the Live meter",
                keys.containsValue(free));
    }

    @Test public void theDefaultOrderHasNoUnlistedGroupAndTheRetiredPagesAreGone() {
        List<String> ids = new ArrayList<>();
        for (NavEntry entry : NavEntry.defaults()) ids.add(entry.id());
        assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat", "party", "key-pops", "timeline", "logging",
            "bridge-review", "settings"), ids);
        List<String> groups = new ArrayList<>();
        for (NavEntry.Group group : NavEntry.Group.values()) groups.add(group.name());
        assertEquals("The unlisted group went with its last page, Statistics", Arrays.asList("CORE", "ADVANCED", "SETTINGS"), groups);
        assertNull("The Statistics page was removed", NavEntry.forId("statistics"));
        assertNull("The Build pointer page was removed", NavEntry.forId("my-info"));
        assertNull("The DPS Logger pointer page was removed", NavEntry.forId("dps-logger"));
    }

    @Test public void theShellsOwnNamesAreReservedAndBadEntriesAreRejected() {
        for (String reserved : new String[] {"advanced", "menu"}) {
            try {
                new NavEntry(reserved, "Reserved", "Collides with nav-" + reserved, 0, NavEntry.Group.CORE, 0);
                fail(reserved + " would collide with the shell's nav-" + reserved + " components");
            } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(reserved)); }
        }
        try { new NavEntry("Bad Id", "Bad", "Bad", 0, NavEntry.Group.CORE, 0); fail(); } catch (IllegalArgumentException expected) { }
        try { new NavEntry("negative", "Bad", "Bad", 0, NavEntry.Group.CORE, -1); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("0 means no shortcut", 0, new NavEntry("advanced-tools", "Tools", "Tools", 0, NavEntry.Group.ADVANCED, 0).shortcut());
        assertNull(NavEntry.forId("no-such-page"));
        assertNull(NavEntry.forId(null));
    }
}
