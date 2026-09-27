package tomato.gui.modern;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavLayoutTest {
    private final Map<String, String> store = new HashMap<>();

    private NavLayout layout() { return new NavLayout(store::get, store::put); }

    private static List<String> ids(List<NavEntry> entries) {
        List<String> ids = new ArrayList<>();
        for (NavEntry entry : entries) ids.add(entry.id());
        return ids;
    }

    @Test public void defaultsFollowTheInterimInformationArchitecture() {
        NavLayout layout = layout();
        assertEquals(Arrays.asList("home", "characters", "runs", "dps-logger", "loot", "quests", "chat"), ids(layout.core()));
        assertEquals(Arrays.asList("party", "key-pops", "timeline", "statistics", "logging", "bridge-review"), ids(layout.advanced()));
        assertEquals(13, layout.settings().page());
        assertFalse(layout.advancedOpen());
        assertEquals(14, layout.landing().page());
        assertTrue("Reading never writes", store.isEmpty());
        String[] titles = NavEntry.titles();
        assertEquals(15, titles.length);
        assertEquals("Party", titles[2]);
        assertEquals("Quests", titles[5]);
        assertEquals("Build", titles[6]);
        assertEquals("Settings", titles[13]);
        assertEquals("Home", titles[14]);
        Set<Integer> pages = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (NavEntry entry : NavEntry.defaults()) {
            assertTrue("Unique page " + entry.page(), pages.add(entry.page()));
            assertTrue("Unique ID " + entry.id(), seen.add(entry.id()));
            assertSame(entry, NavEntry.forPage(entry.page()));
            assertSame(entry, NavEntry.forId(entry.id()));
        }
        assertEquals(15, pages.size());
        NavEntry home = NavEntry.forId("home"), build = NavEntry.forId("my-info");
        assertSame("Home leads the defaults", home, NavEntry.defaults().get(0));
        assertEquals(LineIcon.HOME, home.icon());
        assertEquals(NavEntry.Group.CORE, home.group());
        assertEquals("Build keeps My Info's ID and page", 6, build.page());
        assertEquals(NavEntry.Group.UNLISTED, build.group());
        assertFalse(layout.inCore("my-info"));
        assertFalse(ids(layout.advancedOrder()).contains("my-info"));
        assertNull(NavEntry.forId("no-such-page"));
        try { new NavEntry("Bad Id", 0, "Bad", "Bad", 0, NavEntry.Group.CORE); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void reorderingAndHidingPersistAndReload() {
        NavLayout layout = layout();
        assertTrue(layout.move("chat", -6));
        assertEquals("chat,home,characters,runs,dps-logger,loot,quests", store.get(NavLayout.ORDER_KEY));
        assertFalse("Already first", layout.canMove("chat", -1));
        assertTrue(layout.hide("home"));
        assertEquals("home", store.get(NavLayout.HIDDEN_KEY));
        assertTrue("Moves past hidden rows", layout.move("characters", -1));
        NavLayout reopened = layout();
        assertEquals(Arrays.asList("characters", "chat", "runs", "dps-logger", "loot", "quests"), ids(reopened.core()));
        assertEquals(Collections.singletonList("home"), ids(reopened.hidden()));
        assertEquals("characters", reopened.landing().id());
        assertTrue(reopened.show("home"));
        assertEquals(Arrays.asList("characters", "chat", "home", "runs", "dps-logger", "loot", "quests"), ids(reopened.core()));
        assertFalse("Settings is always reachable", reopened.hide("settings"));
    }

    @Test public void theLastVisibleCoreEntryStaysAndLandingFallsBackToIt() {
        NavLayout layout = layout();
        for (String id : new String[] {"home", "characters", "runs", "dps-logger", "loot", "quests"}) assertTrue(layout.hide(id));
        assertFalse(layout.canHide("chat"));
        assertFalse(layout.hide("chat"));
        assertEquals("chat", layout.landing().id());
        assertTrue("Advanced entries can still be hidden", layout.hide("party"));
        store.put(NavLayout.HIDDEN_KEY, "home,characters,runs,dps-logger,loot,quests,chat");
        assertEquals("A hand-edited file cannot hide every core entry", "home", layout().landing().id());
    }

    @Test public void pinningMovesAdvancedEntriesIntoTheCoreListAndBack() {
        NavLayout layout = layout();
        assertFalse("Core entries are already in the list", layout.pin("chat"));
        assertTrue(layout.pin("timeline"));
        assertEquals("timeline", store.get(NavLayout.PINNED_KEY));
        assertEquals(Arrays.asList("home", "characters", "runs", "dps-logger", "loot", "quests", "chat", "timeline"), ids(layout.core()));
        assertFalse(ids(layout.advanced()).contains("timeline"));
        assertTrue(layout.inCore("timeline"));
        assertTrue(layout.isPinned("timeline"));
        assertTrue(layout.move("timeline", -7));
        assertEquals("timeline", layout().landing().id());
        assertTrue(layout.unpin("timeline"));
        assertEquals("", store.get(NavLayout.PINNED_KEY));
        assertEquals(Arrays.asList("party", "key-pops", "timeline", "statistics", "logging", "bridge-review"), ids(layout.advanced()));
        assertEquals("home", layout.landing().id());
    }

    @Test public void unknownIdsAreIgnoredAndResetRestoresTheDefaults() {
        store.put(NavLayout.ORDER_KEY, "no-such-page,chat,home,settings,party,chat");
        store.put(NavLayout.HIDDEN_KEY, "settings,unknown,logging");
        store.put(NavLayout.ADVANCED_KEY, "true");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("chat", "home", "characters", "runs", "dps-logger", "loot", "quests"), ids(layout.core()));
        assertEquals(Collections.singletonList("logging"), ids(layout.hidden()));
        assertTrue(layout.advancedOpen());
        layout.reset();
        for (String key : new String[] {NavLayout.ORDER_KEY, NavLayout.HIDDEN_KEY, NavLayout.PINNED_KEY, NavLayout.ADVANCED_KEY})
            assertEquals(key, "", store.get(key));
        assertEquals(Arrays.asList("home", "characters", "runs", "dps-logger", "loot", "quests", "chat"), ids(layout().core()));
        assertFalse(layout().advancedOpen());
        layout.setAdvancedOpen(true);
        assertEquals("true", store.get(NavLayout.ADVANCED_KEY));
    }

    @Test public void homeLeadsASavedOrderThatPredatesItWithoutWritingOnRead() {
        store.put(NavLayout.ORDER_KEY, "chat,characters");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("home", "chat", "characters", "runs", "dps-logger", "loot", "quests"), ids(layout.core()));
        assertEquals("home", layout.landing().id());
        assertEquals("Reading never writes", "chat,characters", store.get(NavLayout.ORDER_KEY));
        assertEquals(1, store.size());
        assertTrue(layout.move("quests", -1));
        assertEquals("The next save writes Home into the order, so the prepend happens once",
            "home,chat,characters,runs,dps-logger,quests,loot", store.get(NavLayout.ORDER_KEY));
    }

    @Test public void aSavedOrderThatAlreadyPlacesHomeKeepsIt() {
        store.put(NavLayout.ORDER_KEY, "chat,home,characters");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("chat", "home", "characters", "runs", "dps-logger", "loot", "quests"), ids(layout.core()));
        assertEquals("chat", layout.landing().id());
        assertEquals(1, store.size());
    }

    @Test public void savedBuildEntriesAreIgnoredAndDroppedOnTheNextWrite() {
        store.put(NavLayout.ORDER_KEY, "my-info,characters,home");
        store.put(NavLayout.HIDDEN_KEY, "my-info,loot");
        store.put(NavLayout.PINNED_KEY, "my-info,timeline");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("characters", "home", "runs", "dps-logger", "quests", "chat", "timeline"), ids(layout.core()));
        assertEquals(Collections.singletonList("loot"), ids(layout.hidden()));
        assertFalse(layout.inCore("my-info"));
        assertFalse(layout.isHidden("my-info"));
        assertFalse(layout.isPinned("my-info"));
        assertEquals("Reading never writes", "my-info,characters,home", store.get(NavLayout.ORDER_KEY));
        assertFalse("Build has no row to hide", layout.canHide("my-info"));
        assertFalse(layout.hide("my-info"));
        assertFalse(layout.show("my-info"));
        assertFalse(layout.pin("my-info"));
        assertFalse(layout.move("my-info", 1));
        assertTrue(layout.hide("runs"));
        assertEquals("loot,runs", store.get(NavLayout.HIDDEN_KEY));
        assertTrue(layout.pin("party"));
        assertEquals("timeline,party", store.get(NavLayout.PINNED_KEY));
        assertEquals("characters,home,runs,dps-logger,loot,quests,chat,timeline,party", store.get(NavLayout.ORDER_KEY));
    }
}
