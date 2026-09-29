package tomato.gui.modern;

import java.awt.event.KeyEvent;
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
        assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat"), ids(layout.core()));
        assertEquals(Arrays.asList("party", "key-pops", "timeline", "logging", "bridge-review"), ids(layout.advanced()));
        assertEquals("settings", layout.settings().id());
        assertFalse(layout.advancedOpen());
        assertEquals("home", layout.landing().id());
        assertTrue("Reading never writes", store.isEmpty());
        assertEquals("P6a removed the Build and DPS Logger pointer pages and the Statistics page", 12, NavEntry.defaults().size());
        assertEquals("Party", TestPages.title("party"));
        assertEquals("Quests", TestPages.title("quests"));
        assertEquals("Settings", TestPages.title("settings"));
        assertEquals("Home", TestPages.title("home"));
        Set<Integer> shortcuts = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (NavEntry entry : NavEntry.defaults()) {
            assertTrue("Unique shortcut " + entry.shortcut(), shortcuts.add(entry.shortcut()));
            assertTrue("Unique ID " + entry.id(), seen.add(entry.id()));
            assertSame(entry, NavEntry.forId(entry.id()));
        }
        assertEquals(12, shortcuts.size());
        NavEntry home = NavEntry.forId("home");
        assertSame("Home leads the defaults", home, NavEntry.defaults().get(0));
        assertEquals(LineIcon.HOME, home.icon());
        assertEquals(NavEntry.Group.CORE, home.group());
        assertNull("Build has no page of its own (Alt+7 opens the sheet's Build tab)", NavEntry.forId("my-info"));
        assertFalse("Alt+7 belongs to no page", shortcuts.contains(KeyEvent.VK_7));
        assertFalse(layout.inCore("my-info"));
        assertFalse(ids(layout.advancedOrder()).contains("my-info"));
        assertNull(NavEntry.forId("no-such-page"));
        try { new NavEntry("Bad Id", "Bad", "Bad", 0, NavEntry.Group.CORE, 0); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void reorderingAndHidingPersistAndReload() {
        NavLayout layout = layout();
        assertTrue(layout.move("chat", -5));
        assertEquals("chat,home,characters,runs,loot,quests", store.get(NavLayout.ORDER_KEY));
        assertFalse("Already first", layout.canMove("chat", -1));
        assertTrue(layout.hide("home"));
        assertEquals("home", store.get(NavLayout.HIDDEN_KEY));
        assertTrue("Moves past hidden rows", layout.move("characters", -1));
        NavLayout reopened = layout();
        assertEquals(Arrays.asList("characters", "chat", "runs", "loot", "quests"), ids(reopened.core()));
        assertEquals(Collections.singletonList("home"), ids(reopened.hidden()));
        assertEquals("characters", reopened.landing().id());
        assertTrue(reopened.show("home"));
        assertEquals(Arrays.asList("characters", "chat", "home", "runs", "loot", "quests"), ids(reopened.core()));
        assertFalse("Settings is always reachable", reopened.hide("settings"));
    }

    @Test public void theLastVisibleCoreEntryStaysAndLandingFallsBackToIt() {
        NavLayout layout = layout();
        for (String id : new String[] {"home", "characters", "runs", "loot", "quests"}) assertTrue(layout.hide(id));
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
        assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat", "timeline"), ids(layout.core()));
        assertFalse(ids(layout.advanced()).contains("timeline"));
        assertTrue(layout.inCore("timeline"));
        assertTrue(layout.isPinned("timeline"));
        assertTrue(layout.move("timeline", -6));
        assertEquals("timeline", layout().landing().id());
        assertTrue(layout.unpin("timeline"));
        assertEquals("", store.get(NavLayout.PINNED_KEY));
        assertEquals(Arrays.asList("party", "key-pops", "timeline", "logging", "bridge-review"), ids(layout.advanced()));
        assertEquals("home", layout.landing().id());
    }

    @Test public void unknownIdsAreIgnoredAndResetRestoresTheDefaults() {
        store.put(NavLayout.ORDER_KEY, "no-such-page,chat,home,settings,party,chat");
        store.put(NavLayout.HIDDEN_KEY, "settings,unknown,logging");
        store.put(NavLayout.ADVANCED_KEY, "true");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("chat", "home", "characters", "runs", "loot", "quests"), ids(layout.core()));
        assertEquals(Collections.singletonList("logging"), ids(layout.hidden()));
        assertTrue(layout.advancedOpen());
        layout.reset();
        for (String key : new String[] {NavLayout.ORDER_KEY, NavLayout.HIDDEN_KEY, NavLayout.PINNED_KEY, NavLayout.ADVANCED_KEY})
            assertEquals(key, "", store.get(key));
        assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat"), ids(layout().core()));
        assertFalse(layout().advancedOpen());
        layout.setAdvancedOpen(true);
        assertEquals("true", store.get(NavLayout.ADVANCED_KEY));
    }

    @Test public void homeLeadsASavedOrderThatPredatesItWithoutWritingOnRead() {
        store.put(NavLayout.ORDER_KEY, "chat,characters");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("home", "chat", "characters", "runs", "loot", "quests"), ids(layout.core()));
        assertEquals("home", layout.landing().id());
        assertEquals("Reading never writes", "chat,characters", store.get(NavLayout.ORDER_KEY));
        assertEquals(1, store.size());
        assertTrue(layout.move("quests", -1));
        assertEquals("The next save writes Home into the order, so the prepend happens once",
            "home,chat,characters,runs,quests,loot", store.get(NavLayout.ORDER_KEY));
    }

    @Test public void aSavedOrderThatAlreadyPlacesHomeKeepsIt() {
        store.put(NavLayout.ORDER_KEY, "chat,home,characters");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("chat", "home", "characters", "runs", "loot", "quests"), ids(layout.core()));
        assertEquals("chat", layout.landing().id());
        assertEquals(1, store.size());
    }

    @Test public void savedBuildEntriesAreIgnoredAndDroppedOnTheNextWrite() {
        store.put(NavLayout.ORDER_KEY, "my-info,characters,home");
        store.put(NavLayout.HIDDEN_KEY, "my-info,loot");
        store.put(NavLayout.PINNED_KEY, "my-info,timeline");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("characters", "home", "runs", "quests", "chat", "timeline"), ids(layout.core()));
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
        // Hiding Runs & DPS also hides the live meter, so dps-logger is saved beside it (NavLayout's one-time un-hide).
        assertEquals("loot,runs,dps-logger", store.get(NavLayout.HIDDEN_KEY));
        assertTrue(layout.pin("party"));
        assertEquals("timeline,party", store.get(NavLayout.PINNED_KEY));
        assertEquals("characters,home,runs,loot,quests,chat,timeline,party", store.get(NavLayout.ORDER_KEY));
    }

    @Test public void statisticsAndDpsLoggerAreGoneAndRunsIsRunsAndDps() {
        NavLayout layout = layout();
        NavEntry runs = NavEntry.forId("runs");
        assertEquals(KeyEvent.VK_R, runs.shortcut());
        assertEquals("Runs & DPS", runs.title());
        assertEquals(LineIcon.SWORDS, runs.icon());
        assertEquals("Review runs, dungeons, live damage and recordings.", runs.description());
        assertEquals(NavEntry.Group.CORE, runs.group());
        assertNull("The DPS Logger pointer page was removed (Alt+8 opens the Live meter)", NavEntry.forId("dps-logger"));
        assertNull("The Statistics page was removed (Alt+5 opens Runs & DPS › Dungeons)", NavEntry.forId("statistics"));
        assertEquals("Runs & DPS", TestPages.title("runs"));
        List<NavEntry> defaults = NavEntry.defaults();
        assertEquals("Settings is the tail of the defaults; no unlisted page follows it", Collections.singletonList("settings"),
            ids(defaults.subList(defaults.size() - 1, defaults.size())));
        assertEquals("S7: six core destinations", 6, layout.core().size());
        assertEquals("Advanced (5)", 5, layout.advanced().size());
        for (String id : new String[] {"dps-logger", "statistics"}) {
            assertFalse(id, layout.inCore(id));
            assertFalse(id, ids(layout.advancedOrder()).contains(id));
            assertFalse(id + " has no row to hide", layout.canHide(id));
            assertFalse(layout.hide(id));
            assertFalse(layout.pin(id));
            assertFalse(layout.move(id, -1));
        }
        assertTrue("Nothing above wrote", store.isEmpty());
    }

    /** Statistics (P6a Task 12) and DPS Logger (Task 6) are unknown IDs now: saved values are still read, ignored and dropped. */
    @Test public void savedStatisticsAndDpsLoggerEntriesAreIgnoredAndDroppedOnTheNextWrite() {
        store.put(NavLayout.ORDER_KEY, "dps-logger,characters,statistics,home");
        store.put(NavLayout.HIDDEN_KEY, "statistics,dps-logger,loot");
        store.put(NavLayout.PINNED_KEY, "statistics,timeline");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("characters", "home", "runs", "quests", "chat", "timeline"), ids(layout.core()));
        assertEquals(Collections.singletonList("loot"), ids(layout.hidden()));
        assertEquals(Arrays.asList("party", "key-pops", "logging", "bridge-review"), ids(layout.advancedOrder()));
        for (String id : new String[] {"dps-logger", "statistics"}) {
            assertFalse(id, layout.inCore(id));
            assertFalse(id, layout.isHidden(id));
            assertFalse(id, layout.isPinned(id));
            assertFalse(layout.show(id));
            assertFalse(layout.unpin(id));
        }
        assertEquals("Reading never writes", "dps-logger,characters,statistics,home", store.get(NavLayout.ORDER_KEY));
        assertEquals("statistics,dps-logger,loot", store.get(NavLayout.HIDDEN_KEY));
        assertEquals("statistics,timeline", store.get(NavLayout.PINNED_KEY));
        assertTrue(layout.hide("quests"));
        assertEquals("loot,quests", store.get(NavLayout.HIDDEN_KEY));
        assertTrue(layout.pin("party"));
        assertEquals("timeline,party", store.get(NavLayout.PINNED_KEY));
        assertEquals("characters,home,runs,loot,quests,chat,timeline,party", store.get(NavLayout.ORDER_KEY));
    }

    /**
     * P6a removed the my-info and dps-logger pages. Their IDs stay readable in every saved key and are dropped on the next write,
     * except that dps-logger is still written beside a hidden runs, so a P5b build does not un-hide Runs & DPS.
     */
    @Test public void removedPointerPageIdsAreIgnoredButDpsLoggerIsStillWrittenBesideAHiddenRuns() {
        assertNull(NavEntry.forId("my-info"));
        assertNull(NavEntry.forId("dps-logger"));
        store.put(NavLayout.ORDER_KEY, "dps-logger,my-info,quests,home");
        store.put(NavLayout.HIDDEN_KEY, "my-info,dps-logger,runs");
        store.put(NavLayout.PINNED_KEY, "dps-logger,my-info,party");
        NavLayout layout = layout();
        assertEquals(Arrays.asList("quests", "home", "characters", "loot", "chat", "party"), ids(layout.core()));
        assertEquals("Runs stays hidden: dps-logger, read as a raw string, was hidden beside it", Collections.singletonList("runs"), ids(layout.hidden()));
        for (String id : new String[] {"my-info", "dps-logger"}) {
            assertFalse(id, layout.inCore(id)); assertFalse(id, layout.isHidden(id)); assertFalse(id, layout.isPinned(id));
            assertFalse(id + " has no row", layout.canHide(id));
            assertFalse(layout.hide(id)); assertFalse(layout.show(id)); assertFalse(layout.pin(id)); assertFalse(layout.move(id, 1));
        }
        assertEquals("Reading never writes", "my-info,dps-logger,runs", store.get(NavLayout.HIDDEN_KEY));
        assertTrue(layout.hide("loot"));
        assertEquals("my-info is dropped; dps-logger is written beside the hidden runs", "runs,loot,dps-logger", store.get(NavLayout.HIDDEN_KEY));
        assertTrue(layout.move("home", -1));
        assertEquals("Both IDs leave the saved order", "home,quests,characters,runs,loot,chat,party", store.get(NavLayout.ORDER_KEY));
        assertTrue(layout.unpin("party"));
        assertEquals("…and the pinned list", "", store.get(NavLayout.PINNED_KEY));
        assertTrue(layout.show("runs"));
        assertEquals("A shown runs drops dps-logger too", "loot", store.get(NavLayout.HIDDEN_KEY));
        assertTrue(layout.hide("runs"));
        assertEquals("loot,runs,dps-logger", store.get(NavLayout.HIDDEN_KEY));
        assertTrue("…and it stays hidden when read again", layout().isHidden("runs"));
    }

    @Test public void runsHiddenWhileDpsLoggerStayedVisibleShowsOnceSoTheMeterKeepsARow() {
        store.put(NavLayout.HIDDEN_KEY, "loot,runs");   // saved before P5b: Runs hidden, DPS Logger listed
        NavLayout layout = layout();
        assertFalse("The live meter moved into Runs & DPS, so Runs shows again", layout.isHidden("runs"));
        assertEquals(Arrays.asList("home", "characters", "runs", "quests", "chat"), ids(layout.core()));
        assertEquals(Collections.singletonList("loot"), ids(layout.hidden()));
        assertEquals("Memory only: reading never writes", "loot,runs", store.get(NavLayout.HIDDEN_KEY));
        assertEquals(1, store.size());
        assertTrue(layout.hide("quests"));
        assertEquals("The next save writes Runs as shown", "loot,quests", store.get(NavLayout.HIDDEN_KEY));
        assertFalse(layout().isHidden("runs"));
        assertTrue("The user may hide Runs & DPS again", layout.hide("runs"));
        assertEquals("loot,quests,runs,dps-logger", store.get(NavLayout.HIDDEN_KEY));
        NavLayout reopened = layout();
        assertTrue("...and it stays hidden: the un-hide happens once", reopened.isHidden("runs"));
        assertEquals("Hidden entries list in the default order", Arrays.asList("runs", "loot", "quests"), ids(reopened.hidden()));
        assertTrue(reopened.show("runs"));
        assertEquals("Shown again, dps-logger is dropped with it", "loot,quests", store.get(NavLayout.HIDDEN_KEY));
    }

    /** P6b sidebar drag: a drop names the row's final place among the visible core rows; it reuses {@code move}, one ORDER write. */
    @Test public void moveToPlacesACoreEntryAtAVisibleIndexPastHiddenRowsAndSavesOnce() {
        store.put(NavLayout.HIDDEN_KEY, "loot");
        store.put(NavLayout.PINNED_KEY, "timeline");
        List<String> writes = new ArrayList<>();
        NavLayout layout = new NavLayout(store::get, (key, value) -> { writes.add(key); store.put(key, value); });
        assertEquals(Arrays.asList("home", "characters", "runs", "quests", "chat", "timeline"), ids(layout.core()));
        assertTrue("Up past a row", layout.moveTo("chat", 1));
        assertEquals(Collections.singletonList(NavLayout.ORDER_KEY), writes);
        assertEquals("The hidden row keeps its place between Runs and Quests", "home,chat,characters,runs,loot,quests,timeline", store.get(NavLayout.ORDER_KEY));
        assertTrue("A pinned Advanced row moves like a core row", layout.moveTo("timeline", 0));
        assertEquals("timeline,home,chat,characters,runs,loot,quests", store.get(NavLayout.ORDER_KEY));
        assertTrue("Down: the index is the final place", layout.moveTo("home", 3));
        assertEquals(Arrays.asList("timeline", "chat", "characters", "home", "runs", "quests"), ids(layout.core()));
        assertTrue("Down past the hidden row, to the end", layout.moveTo("runs", 5));
        assertEquals("timeline,chat,characters,home,loot,quests,runs", store.get(NavLayout.ORDER_KEY));
        assertEquals("One ORDER write per move", Collections.nCopies(4, NavLayout.ORDER_KEY), writes);
        assertFalse("The same index is a no-op", layout.moveTo("runs", 5));
        assertEquals("…with no write", 4, writes.size());
        assertEquals("Hidden and pinned are untouched", "loot", store.get(NavLayout.HIDDEN_KEY));
        assertEquals("timeline", store.get(NavLayout.PINNED_KEY));
        assertEquals(Arrays.asList("timeline", "chat", "characters", "home", "quests", "runs"), ids(layout().core()));
    }

    @Test public void moveToRefusesSettingsUnpinnedAdvancedHiddenUnknownAndOutOfRange() {
        store.put(NavLayout.HIDDEN_KEY, "loot");
        NavLayout layout = layout();
        store.clear();
        int last = layout.core().size() - 1;
        assertFalse("Settings stays below the list", layout.moveTo("settings", 0));
        assertFalse("An unpinned Advanced row must be pinned first", layout.moveTo("party", 0));
        assertFalse("A hidden row (even the current page the shell still shows)", layout.moveTo("loot", 0));
        for (String unknown : new String[] {"no-such-page", "my-info", "dps-logger", "statistics", "", null})
            assertFalse(unknown, layout.moveTo(unknown, 0));
        assertFalse("Below the first place", layout.moveTo("chat", -1));
        assertFalse("Past the last place", layout.moveTo("home", last + 1));
        assertFalse("Where it already is", layout.moveTo("home", 0));
        assertTrue("Nothing above wrote", store.isEmpty());
        assertTrue("The last place is in range", layout.moveTo("home", last));
        assertEquals(Arrays.asList("characters", "runs", "quests", "chat", "home"), ids(layout.core()));
    }

    @Test public void runsStaysHiddenWhenDpsLoggerWasHiddenToo() {
        store.put(NavLayout.HIDDEN_KEY, "runs,dps-logger");
        NavLayout layout = layout();
        assertTrue("Both were hidden, so nothing is un-hidden", layout.isHidden("runs"));
        assertEquals(Collections.singletonList("runs"), ids(layout.hidden()));
        assertEquals(Arrays.asList("home", "characters", "loot", "quests", "chat"), ids(layout.core()));
        assertEquals("Reading never writes", "runs,dps-logger", store.get(NavLayout.HIDDEN_KEY));
        assertTrue(layout.hide("loot"));
        assertEquals("runs,loot,dps-logger", store.get(NavLayout.HIDDEN_KEY));
        assertTrue(layout().isHidden("runs"));
    }
}
