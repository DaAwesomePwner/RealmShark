package tomato;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.stats.LootQuery;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import tomato.history.link.VisitRef;

import javax.swing.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Production composition installs the shell navigator with the analytics targets ahead of generic ones. */
public class ShellRouteRegistrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    /** Runs routes below may choose the Table view; the saved choice is restored after. */
    @Rule public final tomato.gui.runs.RunsViewRule runsView = tomato.gui.runs.RunsViewRule.cards();
    private static final String[] RECAP_SECTIONS = {"damage", "loot", "players", "resources", "timeline", "evidence"};
    /**
     * The Runs & DPS tabs, the Live meter's nested tabs, the Recordings tab's saved view (the encounter library's live state) and
     * the Dungeons tab's view; and Loot's tabs (P6a: it opens on Highlights with both tabs shown).
     */
    private static final String[] TAB_PREFERENCES = {"ui.tabs.runs", "ui.tabs.dps", "ux.archive.encounter-library-live", "ui.dungeons.view",
        "ui.tabs.loot"};

    @Test public void createdWorkspaceRegistersAnalyticsTargetsAndCloseUninstallsTheNavigator() throws Exception {
        java.util.Map<String, String> recapSections = new java.util.HashMap<>();
        for (String id : RECAP_SECTIONS) {   // the run recap's section choices: its defaults here, restored after
            String key = tomato.gui.kit.Collapsible.PREFIX + "run-recap-" + id;
            recapSections.put(key, util.PropertiesManager.getProperty(key)); util.PropertiesManager.setProperties(key, "");
        }
        for (String key : TAB_PREFERENCES) {   // Runs & DPS opens on the Feed with every tab shown; restored after
            recapSections.put(key, util.PropertiesManager.getProperty(key)); util.PropertiesManager.setProperties(key, "");
        }
        Field storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true);
        Object previous = storeField.get(null);
        Field previewField = Tomato.class.getDeclaredField("preview"); previewField.setAccessible(true);
        Object previousPreview = previewField.get(null); previewField.set(null, true);
        String temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        SessionStore store = new SessionStore(temp.newFolder().toPath(), false, "synthetic");
        TomatoData data = new TomatoData();
        // An empty temporary journal: with no character the Build route opens Characters (with one it opens the sheet's Build tab).
        tomato.gui.glance.character.SheetFixtures.inject(data, new tomato.backend.data.CharacterJournal(temp.newFolder().toPath().resolve("journal.json")));
        TomatoGUI gui = new TomatoGUI(data);
        AtomicReference<JComponent> shell = new AtomicReference<>();
        try {
            storeField.set(null, store);
            VisitRef visit = new VisitRef(store.currentId(), "journal:1");
            SwingUtilities.invokeAndWait(() -> {
                shell.set(gui.createWorkspace());
                assertEquals("The app lands on the first visible core destination",
                    new tomato.gui.modern.NavLayout().landing().id(), ((tomato.gui.modern.WorkspaceShell) shell.get()).selectedPage());
                Navigator navigator = Navigator.current();
                assertNotSame(Navigator.NONE, navigator);
                assertTrue("Loot resolves an exact visit through the analytics target",
                    navigator.canOpen(Route.to(Destination.LOOT).withVisit(visit)));
                ArchiveQuery<LootQuery.Facets, LootQuery.Sort> query = ArchiveQuery.of(ArchiveQuery.CURRENT, new LootQuery.Facets(), LootQuery.Facets.class, LootQuery.Sort.TIME);
                assertTrue(navigator.canOpen(Route.to(Destination.STATISTICS).withQuery(query)));
                assertFalse("Statistics cannot resolve a visit and says so by rejecting it",
                    navigator.canOpen(Route.to(Destination.STATISTICS).withVisit(visit)));
                assertTrue(navigator.canOpen(Route.to(Destination.LOGGING).withPayload(tomato.gui.logging.LoggingRouteTarget.issuesFor(Destination.RUNS))));
                assertFalse("Only allowlisted packets of the affected view are routable",
                    navigator.canOpen(Route.to(Destination.LOGGING).withPayload(tomato.gui.logging.LoggingRouteTarget.packetFor(Destination.RUNS, "TEXT"))));
                String character = "0".repeat(64) + ":7";
                assertTrue("A character's sheet has its own route",
                    navigator.canOpen(Route.to(Destination.CHARACTER_SHEET).withPayload(new tomato.gui.glance.character.SheetFocus(character, "goals"))));
                assertFalse("A sheet route needs a character", navigator.canOpen(Route.to(Destination.CHARACTER_SHEET)));
                assertTrue(navigator.canOpen(Route.to(Destination.CHARACTERS)));
                assertFalse("The list takes no payload",
                    navigator.canOpen(Route.to(Destination.CHARACTERS).withPayload(new tomato.gui.glance.character.SheetFocus(character, null))));
                tomato.gui.modern.WorkspaceShell workspace = (tomato.gui.modern.WorkspaceShell) shell.get();
                String landing = workspace.selectedPage();
                assertTrue("Build keeps its route although it has no page", navigator.open(Route.to(Destination.MY_INFO)));
                assertEquals("With no character it opens Characters", "characters", workspace.selectedPage());
                assertTrue(navigator.open(Route.to(Destination.HOME)));
                assertEquals("home", workspace.selectedPage());
                assertTrue(navigator.back()); assertEquals("characters", workspace.selectedPage());
                assertTrue(navigator.back()); assertEquals(landing, workspace.selectedPage());

                // Runs routes resolve by shape: the feed and recap targets are tried first, and routes to rows still reach the
                // Table view's targets (a visit: the exact run's row; a query: the archive's query).
                assertEquals("runs", tomato.gui.modern.WorkspaceShell.pageOf(Destination.RUN_RECAP));
                assertTrue("A run recap takes one exact visit", navigator.canOpen(Route.to(Destination.RUN_RECAP).withVisit(visit)));
                assertFalse("…and needs one", navigator.canOpen(Route.to(Destination.RUN_RECAP)));
                assertFalse("…and nothing but the visit", navigator.canOpen(Route.to(Destination.RUN_RECAP).withVisit(visit).withPayload("focus")));
                assertTrue(navigator.canOpen(Route.to(Destination.RUNS)));
                assertTrue(navigator.canOpen(Route.to(Destination.RUNS).withQuery(tomato.gui.activity.ActivityQueries.initial())));
                tomato.gui.runs.RunsPage runs = runsPage(workspace);
                assertNotNull("Page 10 is the Runs page", runs);
                assertTrue(navigator.open(Route.to(Destination.RUNS).withVisit(visit)));
                assertEquals("runs", workspace.selectedPage());
                assertTrue("A Runs route to a row still opens the Table view on it", runs.feed().tableShown());
                assertFalse(runs.recapShown());
                assertTrue(navigator.open(Route.to(Destination.RUN_RECAP).withVisit(visit)));
                assertTrue("RUN_RECAP opens that run's recap", runs.recapShown());
                assertEquals(visit, ((tomato.gui.runs.RunRecapView) runs.recap()).ref());
                assertTrue(navigator.open(Route.to(Destination.RUNS)));
                assertFalse("A plain Runs route brings the feed forward", runs.recapShown());
                assertTrue("…leaving the Table view chosen", runs.feed().tableShown());
                assertTrue(navigator.back()); assertTrue("Back returns to the recap", runs.recapShown());
                assertTrue(navigator.back()); assertFalse("…then to the Table view", runs.recapShown());
                assertTrue(runs.feed().tableShown());
                assertTrue(navigator.back()); assertEquals(landing, workspace.selectedPage());

                // P5b: the runs page is Runs & DPS; the live meter and Resources & buffs moved there (P6a removed the DPS Logger page).
                tomato.gui.runs.RunsDpsPage runsDps = find(workspace, tomato.gui.runs.RunsDpsPage.class);
                assertNotNull("Page 10 is the Runs & DPS page", runsDps);
                assertSame("…whose Feed is the Runs page", runs, runsDps.feed());
                assertEquals("runs", tomato.gui.modern.WorkspaceShell.pageOf(Destination.ENCOUNTER));
                assertEquals("runs", tomato.gui.modern.WorkspaceShell.pageOf(Destination.RESOURCES));
                assertNull("No DPS Logger page remains", tomato.gui.modern.NavEntry.forId("dps-logger"));
                assertTrue("A plain ENCOUNTER route is the Live meter's", navigator.canOpen(Route.to(Destination.ENCOUNTER)));
                for (tomato.gui.runs.RunsTab tab : tomato.gui.runs.RunsTab.values())
                    assertTrue("Each tab has a route: " + tab, navigator.canOpen(Route.to(Destination.RUNS).withPayload(tomato.gui.runs.RunsFocus.of(tab))));
                assertTrue("The Feed's dungeon filter has a route now that the Dungeons tab wires it (P5b Task 12)",
                    navigator.canOpen(Route.to(Destination.RUNS).withPayload(new tomato.gui.runs.RunsFocus(tomato.gui.runs.RunsTab.FEED, "Lost Halls"))));
                assertFalse("A tab route carries nothing else",
                    navigator.canOpen(Route.to(Destination.RUNS).withVisit(visit).withPayload(tomato.gui.runs.RunsFocus.of(tomato.gui.runs.RunsTab.RECORDINGS))));
                assertTrue(navigator.open(Route.to(Destination.RUNS).withPayload(tomato.gui.runs.RunsFocus.of(tomato.gui.runs.RunsTab.RECORDINGS))));
                assertEquals("runs", workspace.selectedPage());
                assertEquals(tomato.gui.runs.RunsTab.RECORDINGS, runsDps.selectedTab());
                assertTrue(navigator.open(Route.to(Destination.ENCOUNTER)));
                assertEquals(tomato.gui.runs.RunsTab.LIVE_METER, runsDps.selectedTab());
                assertTrue(navigator.back());
                assertEquals("Back returns to the tab left", tomato.gui.runs.RunsTab.RECORDINGS, runsDps.selectedTab());
                assertTrue(navigator.back()); assertEquals(landing, workspace.selectedPage());

                // P5b Task 12: the Dungeons tab holds the cards; a Feed route with a dungeon (the cards' Show runs) shows the Feed's
                // cards filtered to that canonical dungeon, and Back returns to Dungeons.
                assertNotNull("The Dungeons tab holds the cards", find(runsDps, tomato.gui.runs.DungeonsView.class));
                assertTrue(navigator.open(Route.to(Destination.RUNS).withPayload(tomato.gui.runs.RunsFocus.of(tomato.gui.runs.RunsTab.DUNGEONS))));
                assertEquals("runs", workspace.selectedPage());
                assertEquals(tomato.gui.runs.RunsTab.DUNGEONS, runsDps.selectedTab());
                assertTrue(navigator.open(Route.to(Destination.RUNS).withPayload(new tomato.gui.runs.RunsFocus(tomato.gui.runs.RunsTab.FEED, "Lost Halls"))));
                assertEquals(tomato.gui.runs.RunsTab.FEED, runsDps.selectedTab());
                assertFalse("The feed's cards", runs.recapShown() || runs.feed().tableShown());
                assertEquals("Lost Halls", runs.feed().query().map());
                assertTrue(navigator.back());
                assertEquals("Back returns to Dungeons", tomato.gui.runs.RunsTab.DUNGEONS, runsDps.selectedTab());
                assertTrue(navigator.back()); assertEquals(landing, workspace.selectedPage());

                // P6a: the loot page is Loot with the tabs Highlights · Explore. Both Loot workspace targets bring Explore forward (a
                // visit, a query or a plain route); a LootFocus route brings its tab; Back returns to the tab left.
                tomato.gui.loot.LootPage loot = find(workspace, tomato.gui.loot.LootPage.class);
                assertNotNull("The loot page is Loot", loot);
                assertEquals("loot", tomato.gui.modern.WorkspaceShell.pageOf(Destination.LOOT));
                assertEquals(tomato.gui.loot.LootTab.HIGHLIGHTS, loot.selectedTab());
                assertTrue(navigator.canOpen(Route.to(Destination.LOOT).withQuery(LootQuery.initial(LootQuery.View.ITEMS, ArchiveQuery.CURRENT))));
                for (tomato.gui.loot.LootTab tab : tomato.gui.loot.LootTab.values())
                    assertTrue("Each Loot tab has a route: " + tab, navigator.canOpen(Route.to(Destination.LOOT).withPayload(new tomato.gui.loot.LootFocus(tab))));
                assertTrue(navigator.open(Route.to(Destination.LOOT).withPayload(new tomato.gui.loot.LootFocus(tomato.gui.loot.LootTab.EXPLORE))));
                assertEquals("loot", workspace.selectedPage());
                assertEquals(tomato.gui.loot.LootTab.EXPLORE, loot.selectedTab());
                assertTrue(navigator.open(Route.to(Destination.LOOT).withPayload(new tomato.gui.loot.LootFocus(tomato.gui.loot.LootTab.HIGHLIGHTS))));
                assertEquals(tomato.gui.loot.LootTab.HIGHLIGHTS, loot.selectedTab());
                assertTrue(navigator.open(Route.to(Destination.LOOT).withVisit(visit)));
                assertEquals("A visit route brings Explore forward", tomato.gui.loot.LootTab.EXPLORE, loot.selectedTab());
            });
            // The visit route's drill summary is read off the EDT; it sits in the Explore tab.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            JTextArea[] summary = new JTextArea[1];
            while (summary[0] == null && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> summary[0] = named(shell.get(), "loot-drill-summary", JTextArea.class));
                if (summary[0] == null) Thread.sleep(20);
            }
            assertNotNull("The Loot visit route shows its drill summary", summary[0]);
            SwingUtilities.invokeAndWait(() -> {
                tomato.gui.loot.LootPage loot = find(shell.get(), tomato.gui.loot.LootPage.class);
                java.awt.Component explore = loot.tabs().component().getComponentAt(loot.tabs().visibleIds().indexOf(tomato.gui.loot.LootTab.EXPLORE.id()));
                assertTrue("loot-drill-summary sits inside Explore", SwingUtilities.isDescendingFrom(summary[0], explore));
                Navigator navigator = Navigator.current();
                tomato.gui.modern.WorkspaceShell workspace = (tomato.gui.modern.WorkspaceShell) shell.get();
                assertTrue(navigator.back());
                assertEquals("loot", workspace.selectedPage());
                assertEquals("Back returns to the tab left", tomato.gui.loot.LootTab.HIGHLIGHTS, loot.selectedTab());
                assertTrue(navigator.back());
                assertEquals(tomato.gui.loot.LootTab.EXPLORE, loot.selectedTab());
                assertTrue(navigator.back());
                assertNotEquals("loot", workspace.selectedPage());
            });
            gui.closeWorkspace();
            SwingUtilities.invokeAndWait(() -> assertSame(Navigator.NONE, Navigator.current()));
        } finally {
            gui.closeWorkspace();
            SwingUtilities.invokeAndWait(() -> { if (shell.get() != null) shell.get().removeNotify(); });
            System.setProperty("java.io.tmpdir", temporaryDirectory);
            storeField.set(null, previous); previewField.set(null, previousPreview); store.close();
            for (java.util.Map.Entry<String, String> saved : recapSections.entrySet())
                util.PropertiesManager.setProperties(saved.getKey(), saved.getValue() == null ? "" : saved.getValue());
        }
    }

    private static <T> T find(java.awt.Container root, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof java.awt.Container) { T found = find((java.awt.Container) child, type); if (found != null) return found; }
        }
        return null;
    }

    private static <T extends java.awt.Component> T named(java.awt.Container root, String name, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof java.awt.Container) { T found = named((java.awt.Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }

    private static tomato.gui.runs.RunsPage runsPage(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof tomato.gui.runs.RunsPage) return (tomato.gui.runs.RunsPage) child;
            if (child instanceof java.awt.Container) { tomato.gui.runs.RunsPage found = runsPage((java.awt.Container) child); if (found != null) return found; }
        }
        return null;
    }
}
