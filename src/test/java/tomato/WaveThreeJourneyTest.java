package tomato;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.incoming.MapInfoPacket;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.DpsData;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.activity.ActivityRoutes;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.RecordedEncounter;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewState;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.gui.runs.RunsDpsPage;
import tomato.gui.runs.RunsTab;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveRow;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.Assert.*;

/**
 * Wave 3 cross-module journeys over the production composition (preview, synthetic history, isolated
 * temporary directory and workspace state): exact visit routes never cross-link same-name visits, Back
 * restores the origin after each hop, foreign references are explicitly unavailable, every route shape the
 * lanes emit is accepted, and a stale load completing after Back cannot replace the restored state.
 */
public class WaveThreeJourneyTest {
    /** Saved view states the composition reads, cleared then restored (P6a Task 12 removed the Statistics workspace). */
    private static final String[] WORKSPACES = {"runs", "timeline", "inspect", "loot", "combat"};
    /**
     * Runs & DPS opens on its first visible tab (the Feed by default), the Live meter's nested tabs keep their saved order, and the
     * Recordings tab saves its view (the encounter library's live state) when the shell is removed, and Loot opens on its first visible
     * tab (Highlights by default, P6a): all four cleared, then restored.
     */
    private static final String[] TAB_PREFERENCES = {"ui.tabs.runs", "ui.tabs.dps", "ux.archive.encounter-library-live", "ui.tabs.loot"};
    private static final String V1 = "journal:v1", V2 = "journal:v2";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    /** The Runs page opens on the run cards; these journeys drive the archive table, its Table view. */
    @Rule public final tomato.gui.runs.RunsViewRule runsView = new tomato.gui.runs.RunsViewRule();

    private Field storeField, previewField;
    private Object previousStore, previousPreview;
    private String previousTmp;
    private final Map<String, String> previousStates = new HashMap<>();
    private final Map<String, String> previousTabs = new HashMap<>();
    private SessionStore store;
    private TomatoGUI gui;
    private WorkspaceShell shell;

    @Before public void compose() throws Exception {
        storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true); previousStore = storeField.get(null);
        previewField = Tomato.class.getDeclaredField("preview"); previewField.setAccessible(true); previousPreview = previewField.get(null);
        previewField.set(null, true);
        for (String name : WORKSPACES) {
            previousStates.put(name, util.PropertiesManager.getProperty("ux.archive." + name));
            util.PropertiesManager.setProperties("ux.archive." + name, "");
        }
        for (String key : TAB_PREFERENCES) { previousTabs.put(key, util.PropertiesManager.getProperty(key)); util.PropertiesManager.setProperties(key, ""); }
        previousTmp = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        store = new SessionStore(temp.newFolder("history").toPath(), true, "synthetic");
        String session = store.currentId();
        store.put("runs", V1, visit(V1, 100_000)); store.put("runs", V2, visit(V2, 160_001));
        for (int i = 1; i <= 6; i++) store.put("runs", "journal:other-" + i, visit("journal:other-" + i, 400_000 + i * 70_000L));
        store.append("timeline", entry("t1-early", V1, 110_000)); store.append("timeline", entry("t1-late", V1, 150_000));
        store.append("timeline", entry("t2-a", V2, 170_000)); store.append("timeline", entry("t2-b", V2, 200_000));
        store.append("timeline", entry("t2-outside", V2, 205_000));
        store.append("loot", drop(V1, 120_000, 1, "First visit sword")); store.append("loot", drop(V2, 180_000, 2, "Second visit sword"));
        store.flush();
        storeField.set(null, store);
        TomatoData data = new TomatoData();
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Lost Halls";
        data.dpsData.add(new DpsData(map, new HashMap<>(), new ArrayList<>(), 60_000, 160_001, null, null,
            new EncounterContext(new VisitRef(session, V2), 21, 160_001)));
        gui = new TomatoGUI(data);
        shell = edt(() -> (WorkspaceShell) gui.createWorkspace());
    }

    @After public void release() throws Exception {
        if (gui != null) gui.closeWorkspace();
        SwingUtilities.invokeAndWait(() -> { if (shell != null) shell.removeNotify(); });
        for (Map.Entry<String, String> state : previousStates.entrySet())
            util.PropertiesManager.setProperties("ux.archive." + state.getKey(), state.getValue() == null ? "" : state.getValue());
        for (Map.Entry<String, String> saved : previousTabs.entrySet())
            util.PropertiesManager.setProperties(saved.getKey(), saved.getValue() == null ? "" : saved.getValue());
        System.setProperty("java.io.tmpdir", previousTmp);
        storeField.set(null, previousStore); previewField.set(null, previousPreview);
        if (store != null) store.close();
    }

    @Test public void sameNameVisitsNeverCrossLinkAndBackRestoresTheOriginAfterEachHop() throws Exception {
        VisitRef second = new VisitRef(store.currentId(), V2);
        ArchiveWorkspace<?, ?, ?> runs = workspace("runs"), timeline = workspace("timeline"), inspect = workspace("inspect"), loot = workspace("loot");
        ViewState<?, ?> origin = reviewQueue(runs);

        assertTrue(open(Route.to(Destination.RUNS).withVisit(second)));
        await(() -> settled(runs) && runs.displayedPage().matches == 1);
        assertEquals(Collections.singletonList(V2), visitIds(runs, row -> field(row, "visitId")));
        backTo(runs, origin, "runs");

        assertTrue(open(ActivityRoutes.timelineAround(second, 175_000, ActivityRoutes.AROUND_MILLIS)));
        assertEquals(WorkspaceShell.pageOf(Destination.TIMELINE), edt(shell::selectedPage));
        await(() -> settled(timeline) && timeline.state().query.bounds().from != null);
        assertEquals("Only the second visit's events inside [145 s, 205 s)", Arrays.asList("t2-a", "t2-b"),
            visitIds(timeline, row -> field(row, "recordId")));
        backTo(runs, origin, "runs");

        assertTrue(open(Route.to(Destination.INSPECT).withVisit(second)));
        await(() -> settled(inspect) && inspect.displayedPage().matches == 1);
        assertEquals(Collections.singletonList(V2), visitIds(inspect, row -> field(row, "visitId")));
        backTo(runs, origin, "runs");

        assertTrue(open(Route.to(Destination.LOOT).withVisit(second)));
        await(() -> settled(loot) && loot.displayedPage().matches == 1);
        assertEquals(Collections.singletonList(V2), visitIds(loot, row -> field(row, "visitId")));
        assertEquals(Collections.singletonList("Second visit sword"), visitIds(loot, row -> field(row, "name")));
        // Loot is a page with Highlights and Explore (P6a): the visit route brings Explore, which holds the Loot workspace, forward.
        assertEquals("The LOOT route brings Explore forward", tomato.gui.loot.LootTab.EXPLORE, edt(() -> lootPage().selectedTab()));
        assertTrue("…whose content is the Loot workspace", edt(() -> SwingUtilities.isDescendingFrom(loot, exploreTab())));
        backTo(runs, origin, "runs");

        assertTrue(open(Route.to(Destination.RESOURCES).withVisit(second)));
        assertEquals(WorkspaceShell.pageOf(Destination.RESOURCES), edt(shell::selectedPage));
        // Resources & buffs is nested in the Live meter tab of Runs & DPS, the origin's own page (P5b): the route brings that tab
        // forward, and Back (below) brings the Feed forward before restoring the Runs table.
        assertEquals("RESOURCES brings the Live meter tab forward", RunsTab.LIVE_METER, edt(() -> runsDps().selectedTab()));
        assertEquals("…on Resources & buffs", "Resources & buffs", edt(() -> {
            JTabbedPane nested = named(shell, JTabbedPane.class, "dps-tabs");
            return nested.getTitleAt(nested.getSelectedIndex());
        }));
        ArchiveWorkspace<?, ?, ?> resources = workspace("combat");
        await(() -> settled(resources) && resources.displayedPage().matches == 1);
        assertEquals(Collections.singletonList(V2), visitIds(resources, row -> field(row, "visitId")));
        backTo(runs, origin, "runs");
        assertFalse(edt(() -> Navigator.current().canGoBack()));
    }

    @Test public void foreignOrDeletedSessionReferencesOpenAnExplicitUnavailableStateNotASubstitute() throws Exception {
        VisitRef foreign = new VisitRef(UUID.randomUUID().toString(), V2); // Same visit ID, absent session.
        ArchiveWorkspace<?, ?, ?> runs = workspace("runs"), loot = workspace("loot"), timeline = workspace("timeline");
        ViewState<?, ?> origin = reviewQueue(runs);
        assertTrue(open(Route.to(Destination.RUNS).withVisit(foreign)));
        await(() -> settled(runs) && runs.displayedPage().matches == 0);
        String detail = edt(() -> named(runs, JTextArea.class, "activity-archive-detail").getText());
        assertTrue(detail, detail.contains("Linked visit unavailable") && detail.contains("same dungeon name"));
        backTo(runs, origin, "runs");

        assertTrue(open(ActivityRoutes.timelineAround(foreign, 175_000, ActivityRoutes.AROUND_MILLIS)));
        await(() -> settled(timeline) && timeline.state().query.bounds().from != null);
        assertEquals(0, (long) edt(() -> timeline.displayedPage().matches));
        backTo(runs, origin, "runs");

        assertTrue(open(Route.to(Destination.LOOT).withVisit(foreign)));
        await(() -> settled(loot));
        edt(() -> {
            assertEquals("No other session's loot is substituted", 0, loot.displayedPage().matches);
            assertTrue(named(loot, JTextArea.class, "loot-drill-summary").getText().contains("Linked run unavailable here"));
            // P6a: the drill summary sits in Loot's Explore tab, which the route brought forward.
            assertEquals(tomato.gui.loot.LootTab.EXPLORE, lootPage().selectedTab());
            assertTrue("loot-drill-summary sits inside Explore",
                SwingUtilities.isDescendingFrom(named(shell, JTextArea.class, "loot-drill-summary"), exploreTab()));
            return null;
        });
        backTo(runs, origin, "runs");
    }

    @Test public void everyRouteShapeTheLanesEmitIsAcceptedByTheProductionNavigator() throws Exception {
        VisitRef second = new VisitRef(store.currentId(), V2);
        edt(() -> {
            Navigator navigator = Navigator.current();
            Map<String, Route> shapes = new LinkedHashMap<>();
            shapes.put("Loot / fame: Open recorded run", Route.to(Destination.RUNS).withVisit(second));
            for (Destination destination : new Destination[]{Destination.RUNS, Destination.INSPECT, Destination.TIMELINE, Destination.RESOURCES, Destination.LOOT})
                shapes.put("Run workbench: " + destination, ActivityRoutes.visit(destination, second));
            shapes.put("Run workbench: Timeline around completion", ActivityRoutes.timelineAround(second, 170_000, ActivityRoutes.AROUND_MILLIS));
            shapes.put("Resources: selected window to Timeline", Route.to(Destination.TIMELINE).withVisit(second).withBounds(165_000L, 175_000L));
            for (Destination destination : new Destination[]{Destination.RUNS, Destination.TIMELINE, Destination.RESOURCES})
                shapes.put("DPS encounter: " + destination, Route.to(destination).withVisit(second));
            RecordedEncounter recorded = DpsGUI.recordedEncounters().get(0);
            assertTrue(recorded.link.linked());
            shapes.put("My Info: recorded local row", recorded.localRowRoute());
            shapes.put("Key pops: dungeon notifications", Route.to(Destination.NOTIFICATIONS)
                .withPayload(tomato.gui.notifications.NotificationFocus.dungeon("Lost Halls")));
            shapes.put("Chat / loot: alert draft", Route.to(Destination.ALERT_DRAFT).withPayload(
                tomato.realmshark.AlertRules.Draft.item(tomato.realmshark.AlertRules.Mode.ITEM_ID, "2", 2, "Second visit sword", "Loot · synthetic")));
            shapes.put("Diagnostics: Logging issues", Route.to(Destination.LOGGING)
                .withPayload(tomato.gui.logging.LoggingRouteTarget.issuesFor(Destination.RUNS)));
            for (Map.Entry<String, Route> shape : shapes.entrySet())
                assertTrue(shape.getKey() + " " + shape.getValue(), navigator.canOpen(shape.getValue()));
            assertFalse("An unverified row is never routable", navigator.canOpen(Route.to(Destination.ENCOUNTER).withRecording(recorded.recordingId, 99)));
            assertFalse("Unknown recordings are rejected", navigator.canOpen(Route.to(Destination.ENCOUNTER).withRecording("missing", 21)));
            return null;
        });
    }

    @Test public void aStaleLoadCompletingAfterBackCannotReplaceTheRestoredState() throws Exception {
        VisitRef second = new VisitRef(store.currentId(), V2);
        ArchiveWorkspace<?, ?, ?> runs = workspace("runs");
        ViewState<?, ?> origin = reviewQueue(runs);
        long originMatches = edt(() -> runs.displayedPage().matches);
        for (Destination destination : new Destination[]{Destination.RUNS, Destination.LOOT, Destination.TIMELINE}) {
            // The destination load starts and is superseded by Back before it can complete.
            assertTrue(edt(() -> Navigator.current().open(Route.to(destination).withVisit(second)) && Navigator.current().back()));
            await(() -> settled(runs));
            Thread.sleep(300);
            edt(() -> {
                assertEquals(destination + ": restored state survives the stale completion", origin.toJson(), runs.state().toJson());
                assertEquals(originMatches, runs.displayedPage().matches);
                assertEquals("runs", shell.selectedPage());
                return null;
            });
        }
    }

    @Test public void staleKeyPopFocusBackNeverPopsALaterNavigationsOrigin() throws Exception {
        VisitRef second = new VisitRef(store.currentId(), V2);
        tomato.gui.notifications.NotificationsGUI notifications = edt(() -> find(shell, tomato.gui.notifications.NotificationsGUI.class));
        JTextField search = edt(() -> named(notifications, JTextField.class, "sound-dungeon-search"));
        edt(() -> { search.setText("Shat"); shell.select("key-pops"); return null; });
        assertTrue(open(Route.to(Destination.NOTIFICATIONS).withPayload(tomato.gui.notifications.NotificationFocus.dungeon("Lost Halls"))));
        edt(() -> {
            assertEquals("settings", shell.selectedPage());
            assertEquals("Lost Halls", search.getText());
            assertTrue(named(notifications, JPanel.class, "sound-dungeon-focus-banner").isVisible());
            assertTrue(Navigator.current().back()); // The user leaves with the shell Back, not the banner.
            assertEquals("key-pops", shell.selectedPage());
            return null;
        });
        ArchiveWorkspace<?, ?, ?> runs = workspace("runs");
        ViewState<?, ?> origin = reviewQueue(runs);
        assertTrue(open(Route.to(Destination.LOOT).withVisit(second)));
        edt(() -> {
            assertEquals("loot", shell.selectedPage());
            shell.select("settings"); // Revisit Notifications from the sidebar; the old banner may still be up.
            JButton stale = named(notifications, JButton.class, "sound-dungeon-focus-back");
            assertTrue("The stale banner is still showing", named(notifications, JPanel.class, "sound-dungeon-focus-banner").isVisible()); stale.doClick();
            assertEquals("The stale banner Back did not navigate", "settings", shell.selectedPage());
            assertTrue("The Runs -> Loot origin is still available", Navigator.current().canGoBack());
            assertFalse(named(notifications, JPanel.class, "sound-dungeon-focus-banner").isVisible());
            assertEquals("Pre-focus filter restored", "Shat", search.getText());
            return null;
        });
        backTo(runs, origin, "runs");
    }

    @Test public void leavingTheNotificationsPageEndsTheHandoffFocusAndRestoresFilters() throws Exception {
        Assume.assumeFalse("Needs a real window to switch cards", GraphicsEnvironment.isHeadless());
        JFrame frame = edt(() -> {
            tomato.gui.notifications.NotificationsGUI page = new tomato.gui.notifications.NotificationsGUI();
            JPanel cards = new JPanel(new CardLayout()); cards.add(page, "notifications"); cards.add(new JPanel(), "other");
            JFrame window = new JFrame("Notifications focus"); window.setContentPane(cards); window.setSize(900, 600); window.setVisible(true);
            named(page, JTextField.class, "sound-dungeon-search").setText("Shat");
            assertTrue(page.focusDungeon("Lost Halls", () -> fail("Leaving the page must not navigate")));
            return window;
        });
        try {
            edt(() -> {
                JPanel cards = (JPanel) frame.getContentPane();
                tomato.gui.notifications.NotificationsGUI page = find(cards, tomato.gui.notifications.NotificationsGUI.class);
                assertTrue(page.isShowing());
                ((CardLayout) cards.getLayout()).show(cards, "other");
                assertFalse(named(page, JPanel.class, "sound-dungeon-focus-banner").isVisible());
                assertEquals("Shat", named(page, JTextField.class, "sound-dungeon-search").getText());
                return null;
            });
        } finally { edt(() -> { frame.dispose(); return null; }); }
    }

    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type); if (found != null) return found; }
        }
        return null;
    }

    /** Origin review queue: saved Runs in the current session with the third row selected. */
    private ViewState<?, ?> reviewQueue(ArchiveWorkspace<?, ?, ?> runs) throws Exception {
        edt(() -> { shell.select("runs"); runs.showSaved(); return null; });
        await(() -> settled(runs) && runs.displayedPage().matches == 8);
        edt(() -> { select(runs, 2); return null; });
        ViewState<?, ?> origin = edt(runs::state);
        assertEquals(1, origin.selected.size());
        return origin;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void select(ArchiveWorkspace runs, int row) {
        ArchiveRow.Ref ref = ((ArchiveRow<?>) runs.displayedPage().rows.get(row)).ref;
        ViewState state = runs.state();
        runs.restore(state.withPosition(state.tab, Collections.singletonList(ref), ref, 0));
    }
    private void backTo(ArchiveWorkspace<?, ?, ?> runs, ViewState<?, ?> origin, String page) throws Exception {
        assertTrue(edt(() -> Navigator.current().back()));
        await(() -> settled(runs) && runs.state().query.equals(origin.query));
        edt(() -> {
            assertEquals(page, shell.selectedPage());
            // Every origin here is the Runs table in the Feed tab of Runs & DPS: Back brings that tab forward first.
            if (page.equals("runs")) assertEquals("Back returns to the Feed tab", RunsTab.FEED, runsDps().selectedTab());
            assertEquals("Back restores query, page, selection and scroll anchor", origin.toJson(), runs.state().toJson());
            JTable table = named(runs, JTable.class, "saved-activity-table");
            assertEquals(origin.selected.get(0), runs.displayedPage().rows.get(table.convertRowIndexToModel(table.getSelectedRow())).ref);
            return null;
        });
    }
    /** Loot (P6a): the Highlights and Explore tabs. EDT. */
    private tomato.gui.loot.LootPage lootPage() {
        tomato.gui.loot.LootPage page = named(shell, tomato.gui.loot.LootPage.class, "loot-page");
        assertNotNull("The loot page is Loot", page);
        return page;
    }
    /** Loot's Explore tab content. EDT. */
    private Component exploreTab() {
        tomato.gui.loot.LootPage page = lootPage();
        return page.tabs().component().getComponentAt(page.tabs().visibleIds().indexOf(tomato.gui.loot.LootTab.EXPLORE.id()));
    }
    /** Page 10, Runs & DPS. EDT. */
    private RunsDpsPage runsDps() {
        RunsDpsPage page = named(shell, RunsDpsPage.class, "runs-dps-page");
        assertNotNull("Page 10 is the Runs & DPS page", page);
        return page;
    }
    private static boolean settled(ArchiveWorkspace<?, ?, ?> workspace) { return !workspace.loading() && workspace.displayedPage() != null; }
    private boolean open(Route route) throws Exception { return edt(() -> Navigator.current().open(route)); }
    private ArchiveWorkspace<?, ?, ?> workspace(String name) throws Exception {
        ArchiveWorkspace<?, ?, ?> found = edt(() -> named(shell, ArchiveWorkspace.class, name + "-session-view"));
        assertNotNull(name + " is a saved-history workspace in preview", found);
        return found;
    }
    private static List<String> visitIds(ArchiveWorkspace<?, ?, ?> workspace, Function<Object, String> value) throws Exception {
        return edt(() -> { List<String> ids = new ArrayList<>(); for (Object row : workspace.displayedPage().rows) ids.add(value.apply(((ArchiveRow<?>) row).value)); return ids; });
    }
    private static String field(Object row, String name) {
        try { Field f = row.getClass().getField(name); return Objects.toString(f.get(row), null); }
        catch (ReflectiveOperationException e) { throw new AssertionError("Row has no field " + name, e); }
    }

    private static ActivityJournal.Visit visit(String id, long start) {
        ActivityJournal.Visit v = new ActivityJournal.Visit(); v.id = id; v.map = "Lost Halls";
        v.started = start; v.lastSeen = v.ended = start + 60_000; v.status = "Left"; v.endReason = "Left area";
        return v;
    }
    private static ActivityJournal.Entry entry(String id, String visit, long time) {
        ActivityJournal.Entry e = new ActivityJournal.Entry(); e.id = id; e.visitId = visit; e.map = "Lost Halls"; e.time = time;
        e.kind = "Equipment changed"; e.detail = "Synthetic"; e.values = new LinkedHashMap<>(); return e;
    }
    /** A saved loot bag in the journal's JSON shape (LootDashboard.Drop), linked to one visit. */
    private static JsonObject drop(String visit, long time, int item, String name) {
        JsonObject enchants = new JsonObject(); enchants.addProperty("slots", 0); enchants.addProperty("applied", 0);
        JsonObject value = new JsonObject(); value.addProperty("id", item); value.addProperty("name", name); value.addProperty("tier", "UT");
        value.addProperty("potion", false); value.addProperty("ut", true); value.addProperty("st", false); value.addProperty("highTier", false);
        value.add("enchants", enchants);
        JsonArray key = new JsonArray(); key.add(item); key.add(0); key.add(0); value.add("key", key);
        JsonArray items = new JsonArray(); items.add(value);
        JsonObject bag = new JsonObject(); bag.addProperty("visitId", visit); bag.addProperty("bag", "White"); bag.addProperty("dungeon", "Lost Halls");
        bag.addProperty("dropper", "Synthetic boss"); bag.addProperty("time", time); bag.add("items", items);
        return bag;
    }

    private interface Checked<T> { T get() throws Exception; }
    private static <T> T edt(Checked<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(body.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for EDT state");
    }
    private static <T> T named(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
