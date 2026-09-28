package tomato.gui.runs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.KitButton;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.route.ShellNavigator;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveRow;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.runs.RunFeedViewTest.await;
import static tomato.gui.runs.RunFeedViewTest.edt;
import static tomato.gui.runs.RunFeedViewTest.named;

/**
 * Routes into the Runs page over the real recap builder and synthetic saved runs (RunFixtures): which routes the feed and recap
 * targets accept, opening the recap (loading first, built off the EDT, Damage expanded), the newest-request guard, the in-place
 * recording choice, Back to the feed or the Table view exactly as left (with the workspace's own state), "‹ Runs", a recap opened
 * from another page that Back leaves, plain Runs routes, restore never showing a view the state did not show, unreadable runs.
 * A real ShellNavigator with WorkspaceShell.pageOf; fake Home (14), Loot (8) and Table view targets. Preferences are isolated or
 * restored (ui.collapse.run-recap-*, the feed's view in a map).
 */
public class RunsRouteTargetTest {
    private static final String[] SECTIONS = {"damage", "loot", "players", "resources", "timeline", "evidence"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> saved = new HashMap<>(), prefs = new HashMap<>(), modes = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(modes::get, modes::put);   // never the application's ui.mode
    private final List<RunsPage> pages = new ArrayList<>();
    private final List<SessionStore> stores = new ArrayList<>();

    @Before public void isolate() {
        for (String id : SECTIONS) {
            String key = Collapsible.PREFIX + "run-recap-" + id;
            saved.put(key, PropertiesManager.getProperty(key));
            PropertiesManager.setProperties(key, "");
        }
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (RunsPage page : pages) page.close(); });
        for (SessionStore store : stores) store.close();
        for (Map.Entry<String, String> entry : saved.entrySet()) PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
    }

    /** The real recap builder over RunFixtures' saved runs; counts builds, records their threads, can be held or fail. */
    static final class Builds implements RunsRouteTarget.Recaps {
        final RunRecapBuilder inner;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> onEdt = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gate;
        volatile IOException fail;
        Builds(RunRecapBuilder inner) { this.inner = inner; }
        @Override public RunRecapModel build(VisitRef ref, String recordingId, Cancellation cancel) throws IOException {
            calls.add(ref.visitId + "/" + recordingId);
            onEdt.add(SwingUtilities.isEventDispatchThread());
            CountDownLatch wait = gate;
            if (wait != null) try { wait.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (fail != null) throw fail;
            return inner.build(ref, recordingId, cancel);
        }
    }

    /** A page's route target as the navigator sees it: records opens and restores; its state is whatever the test sets. */
    static final class Fake implements RouteTarget {
        final Destination destination;
        final List<Route> opened = new ArrayList<>();
        final List<Object> restored = new ArrayList<>();
        Object state;
        Fake(Destination destination, Object state) { this.destination = destination; this.state = state; }
        @Override public Destination destination() { return destination; }
        @Override public Object captureState() { return state; }
        @Override public void open(Route route) { opened.add(route); }
        @Override public void restoreState(Object value) { restored.add(value); state = value; }
    }

    /** One Runs page with its targets registered as TomatoGUI registers them, on a real navigator starting on Home (14). */
    final class Shell {
        final int[] selected = {14};
        final ShellNavigator navigator = new ShellNavigator(() -> selected[0], page -> selected[0] = page, WorkspaceShell::pageOf, ShellNavigator.DEFAULT_CAPACITY);
        final Fake home = new Fake(Destination.HOME, null), loot = new Fake(Destination.LOOT, "loot-state"), table = new Fake(Destination.RUNS, "workspace-1");
        final RunsPage page;
        final Builds builds;
        Supplier<RunsRouteTarget.Recaps> recaps;
        final RouteTarget runs, recap;

        Shell() throws Exception {
            Path root = temp.newFolder().toPath();
            RunFixtures.write(root);
            SessionStore store = new SessionStore(root, false, "fixture");
            stores.add(store);
            builds = new Builds(new RunRecapBuilder(store, HomeHistoryFixture.ZONE, () -> RunFixtures.NOW));
            recaps = () -> builds;
            page = edt(() -> new RunsPage(new RunFeedView(new JPanel(), () -> null, HomeHistoryFixture.ZONE, mode, prefs::get, prefs::put)));
            pages.add(page);
            List<RouteTarget> targets = edt(() -> {
                navigator.register(home); navigator.register(loot);
                navigator.register(page.tableRoutes(table));   // the Table view's row routes, registered first as in TomatoGUI
                List<RouteTarget> made = RunsRouteTarget.of(page, table, () -> recaps.get(), () -> new RunRecapView(mode), navigator);
                for (RouteTarget target : made) navigator.register(target);
                return made;
            });
            runs = target(targets, Destination.RUNS);
            recap = target(targets, Destination.RUN_RECAP);
        }

        RunRecapView view() { return (RunRecapView) page.recap(); }
        boolean open(Route route) throws Exception { return edt(() -> navigator.open(route)); }
        void settled(VisitRef ref) throws Exception {
            await("the recap of " + ref.visitId, () -> view() != null && !view().loading() && view().model() != null && view().model().ref().equals(ref));
        }
    }

    private static RouteTarget target(List<RouteTarget> targets, Destination destination) {
        for (RouteTarget target : targets) if (target.destination() == destination) return target;
        throw new AssertionError("No " + destination + " target");
    }

    private static Route recap(VisitRef ref) { return Route.to(Destination.RUN_RECAP).withVisit(ref); }

    @Test public void theRecapTakesOnlyAnExactVisitAndPlainRunsOnlyNothing() throws Exception {
        Shell shell = new Shell();
        assertEquals("The recap is a card on the Runs page", 10, WorkspaceShell.pageOf(Destination.RUN_RECAP));
        edt(() -> {
            assertTrue(shell.recap.accepts(recap(RunFixtures.A1)));
            assertFalse("A recap needs a run", shell.recap.accepts(Route.to(Destination.RUN_RECAP)));
            assertFalse("…an exact saved one", shell.recap.accepts(recap(new VisitRef("not-a-session", "v1"))));
            assertFalse(shell.recap.accepts(recap(RunFixtures.A1).withQuery(ActivityQueries.initial())));
            assertFalse(shell.recap.accepts(recap(RunFixtures.A1).withRecord(new ArchiveRow.Ref(RunFixtures.A, "runs", "v1", ""))));
            assertFalse("The recording choice is the view's own state", shell.recap.accepts(recap(RunFixtures.A1).withRecording("r-v1-long", 2)));
            assertFalse(shell.recap.accepts(recap(RunFixtures.A1).withBounds(1L, 2L)));
            assertFalse(shell.recap.accepts(recap(RunFixtures.A1).withPayload("focus")));
            assertFalse("Another destination is not the recap's", shell.recap.accepts(Route.to(Destination.RUNS).withVisit(RunFixtures.A1)));

            assertTrue("A plain Runs route brings the feed forward", shell.runs.accepts(Route.to(Destination.RUNS)));
            assertFalse("A Runs row route stays with the Table view", shell.runs.accepts(Route.to(Destination.RUNS).withVisit(RunFixtures.A1)));
            assertFalse(shell.runs.accepts(Route.to(Destination.RUNS).withQuery(ActivityQueries.initial())));
            assertFalse(shell.runs.accepts(Route.to(Destination.RUNS).withPayload("focus")));
            assertFalse(shell.runs.accepts(Route.to(Destination.RUNS).withBounds(1L, 2L)));

            assertTrue("The navigator resolves each shape", shell.navigator.canOpen(recap(RunFixtures.A1)));
            try { shell.recap.open(Route.to(Destination.RUN_RECAP)); fail("An unsupported route is rejected, not approximated"); }
            catch (IllegalArgumentException expected) { }
            assertNull("A rejected route builds nothing", shell.page.recap());
            try { shell.recap.restoreState("foreign"); fail("A foreign state is rejected"); } catch (IllegalArgumentException expected) { }
            return null;
        });
        shell.recaps = () -> null;   // no saved history is open
        assertFalse("Without saved history the recap is rejected, so Home falls back to the feed",
            edt(() -> shell.navigator.canOpen(recap(RunFixtures.A1))));
        assertTrue(edt(() -> shell.navigator.canOpen(Route.to(Destination.RUNS))));
    }

    @Test public void openShowsLoadingThenTheRunBuiltOffTheEdtWithDamageOpenAndBackToAnotherPageLeavesTheRecap() throws Exception {
        PropertiesManager.setProperties(Collapsible.PREFIX + "run-recap-damage", "false");   // the user collapsed Damage earlier
        Shell shell = new Shell();
        shell.builds.gate = new CountDownLatch(1);
        assertTrue(shell.open(recap(RunFixtures.A1)));
        edt(() -> {
            assertEquals("The recap opens on the Runs page", 10, shell.selected[0]);
            assertTrue(shell.page.recapShown());
            assertTrue("Loading first; nothing of another run shows", shell.view().loading());
            assertEquals(RunFixtures.A1, shell.view().ref());
            Collapsible damage = named(shell.view(), "run-recap-damage", Collapsible.class);
            assertTrue("Opening a run is explicit navigation to its damage breakdown", damage.expanded());
            assertEquals("true", PropertiesManager.getProperty(Collapsible.PREFIX + "run-recap-damage"));
            assertEquals("Back returns to Home", 14, shell.navigator.backPage());
            return null;
        });
        shell.builds.gate.countDown();
        shell.settled(RunFixtures.A1);
        edt(() -> {
            assertEquals(List.of("v1/null"), shell.builds.calls);
            assertEquals("Built off the EDT", List.of(false), shell.builds.onEdt);
            assertEquals("The Damage section shows the longest recording", "r-v1-long", shell.view().model().damage().selected());
            assertNotNull("…with the verified local row", shell.view().model().damage().local());
            assertEquals(new RunsState(true, RunFixtures.A1, null, false, "workspace-1"), shell.recap.captureState());

            assertTrue(shell.navigator.back());
            assertEquals(14, shell.selected[0]);
            assertFalse("Back left the Runs page, so it shows what it showed before: the feed", shell.page.recapShown());
            assertFalse("…on Cards, as it was", shell.page.feed().tableShown());
            assertTrue("The Table view's state was not touched", shell.table.restored.isEmpty());
            assertTrue("Opening the page's feed again", shell.navigator.open(Route.to(Destination.RUNS)));
            assertEquals(10, shell.selected[0]);
            assertFalse(shell.page.recapShown());
            return null;
        });
    }

    @Test public void fromTheFeedBackAndTheBackLinkReturnToTheFeedAsLeft() throws Exception {
        Shell shell = new Shell();
        edt(() -> { shell.selected[0] = 10; return null; });
        assertTrue(shell.open(recap(RunFixtures.A1)));
        shell.settled(RunFixtures.A1);
        edt(() -> {
            assertEquals(1, shell.navigator.depth());
            assertEquals("Back returns to this page's feed", 10, shell.navigator.backPage());
            named(shell.view(), "run-recap-back", KitButton.class).doClick();
            assertFalse("‹ Runs shows the feed", shell.page.recapShown());
            assertEquals("…by going Back: the entry that led here is used, not left behind", 0, shell.navigator.depth());
            assertEquals(10, shell.selected[0]);
            assertFalse(shell.page.feed().tableShown());

            shell.page.showTable();   // the user's Table view, with the workspace in some state
            return null;
        });
        assertTrue(shell.open(recap(RunFixtures.A2)));
        shell.settled(RunFixtures.A2);
        edt(() -> {
            assertTrue(shell.page.recapShown());
            assertTrue("The Table view stays chosen behind the recap", shell.page.feed().tableShown());
            shell.table.state = "workspace-2";   // a later row route changed the workspace
            assertTrue(shell.navigator.back());
            assertFalse(shell.page.recapShown());
            assertTrue("Back returns to the Table view", shell.page.feed().tableShown());
            assertEquals("…with the workspace's state as it was left", List.of("workspace-1"), shell.table.restored);
            return null;
        });
    }

    @Test public void afterARowRouteTheTableViewsCaptureAlsoCountsAsTheFeedForTheBackLink() throws Exception {
        Shell shell = new Shell();
        edt(() -> {
            shell.selected[0] = 10;
            assertTrue("A Runs row route still reaches the Table view's target", shell.navigator.open(Route.to(Destination.RUNS).withVisit(RunFixtures.A1)));
            assertEquals(RunFixtures.A1, shell.table.opened.get(0).visit);
            assertTrue(shell.page.feed().tableShown());
            assertFalse(shell.page.recapShown());
            shell.page.showCards();   // the user went back to the cards; the Table view's target is still the page's shown one
            return null;
        });
        assertTrue(shell.open(recap(RunFixtures.A2)));
        shell.settled(RunFixtures.A2);
        edt(() -> {
            assertEquals(2, shell.navigator.depth());
            named(shell.view(), "run-recap-back", KitButton.class).doClick();
            assertEquals("‹ Runs went Back through the entry to the feed", 1, shell.navigator.depth());
            assertFalse(shell.page.recapShown());
            assertFalse("…on the cards it left", shell.page.feed().tableShown());
            return null;
        });
    }

    @Test public void fromAnotherPageTheBackLinkShowsTheFeedInPlaceAndBackStillReturnsThere() throws Exception {
        Shell shell = new Shell();
        assertTrue(shell.open(recap(RunFixtures.A1)));
        shell.settled(RunFixtures.A1);
        edt(() -> {
            named(shell.view(), "run-recap-back", KitButton.class).doClick();
            assertFalse("‹ Runs leads to the Runs feed, never to Home", shell.page.recapShown());
            assertEquals(10, shell.selected[0]);
            assertEquals("The entry back to Home stays", 1, shell.navigator.depth());
            assertEquals(14, shell.navigator.backPage());
            assertTrue(shell.navigator.back());
            assertEquals(14, shell.selected[0]);
            return null;
        });
    }

    @Test public void aRecordingChoiceRebuildsInPlaceAndBackToTheRecapKeepsIt() throws Exception {
        Shell shell = new Shell();
        edt(() -> { shell.selected[0] = 10; return null; });
        assertTrue(shell.open(recap(RunFixtures.A1)));
        shell.settled(RunFixtures.A1);
        edt(() -> {
            @SuppressWarnings("unchecked")
            JComboBox<RunRecapModel.Damage.Recording> picker = named(shell.view(), "run-recap-recording", JComboBox.class);
            assertEquals("Both recordings of the run are offered", 2, picker.getItemCount());
            picker.setSelectedIndex(1);
            assertFalse("Another recording rebuilds in place, without the loading state", shell.view().loading());
            assertEquals("…and without a Back entry", 1, shell.navigator.depth());
            return null;
        });
        await("the other recording", () -> "r-v1-short".equals(shell.view().model().damage().selected()));
        edt(() -> {
            assertEquals(List.of("v1/null", "v1/r-v1-short"), shell.builds.calls);
            assertEquals(new RunsState(true, RunFixtures.A1, "r-v1-short", false, "workspace-1"), shell.recap.captureState());
            named(shell.view(), "run-recap-open-loot", KitButton.class).doClick();
            assertEquals("The recap's links go through the navigator", 8, shell.selected[0]);
            assertEquals(RunFixtures.A1, shell.loot.opened.get(0).visit);
            assertEquals(2, shell.navigator.depth());
            assertTrue(shell.navigator.back());
            assertEquals(10, shell.selected[0]);
            assertTrue("Back returns to the recap", shell.page.recapShown());
            assertEquals(RunFixtures.A1, shell.view().ref());
            assertEquals("…on the recording chosen", "r-v1-short", shell.view().model().damage().selected());
            assertEquals("…as it was left, without reading it again", 2, shell.builds.calls.size());
            return null;
        });
    }

    @Test public void theNewestRequestWinsAndAStaleBuildNeverReplacesIt() throws Exception {
        Shell shell = new Shell();
        shell.builds.gate = new CountDownLatch(1);
        assertTrue(shell.open(recap(RunFixtures.A1)));
        await("the first build to start", () -> !shell.builds.calls.isEmpty());
        assertTrue(shell.open(recap(RunFixtures.A2)));
        edt(() -> { assertEquals(RunFixtures.A2, shell.view().ref()); assertTrue(shell.view().loading()); return null; });
        shell.builds.gate.countDown();
        shell.settled(RunFixtures.A2);
        Thread.sleep(200);
        edt(() -> {
            assertEquals("The older build's completion is inert", RunFixtures.A2, shell.view().model().ref());
            assertEquals("Snake Pit", shell.view().model().header().mapName());
            return null;
        });
    }

    @Test public void plainRunsAndRestoreShowOnlyTheViewTheStateShowed() throws Exception {
        Shell shell = new Shell();
        edt(() -> { shell.selected[0] = 10; shell.page.showTable(); return null; });
        assertTrue(shell.open(recap(RunFixtures.A1)));
        shell.settled(RunFixtures.A1);
        edt(() -> {
            assertTrue(shell.navigator.open(Route.to(Destination.RUNS)));
            assertFalse("A plain Runs route brings the feed forward", shell.page.recapShown());
            assertTrue("…and leaves Cards or Table as chosen", shell.page.feed().tableShown());

            shell.page.showCards();
            Object cards = shell.runs.captureState();
            assertEquals(RunsState.feed(false, "workspace-1"), cards);
            shell.page.showTable();   // the user switched views since
            shell.runs.restoreState(cards);
            assertFalse("A hidden Table view is not shown by restore", shell.page.feed().tableShown());
            assertFalse(shell.page.recapShown());

            RunsState recapOnCards = new RunsState(true, RunFixtures.A1, null, false, "workspace-1");
            shell.recap.restoreState(recapOnCards);
            assertTrue(shell.page.recapShown());
            assertFalse("Nor behind a restored recap", shell.page.feed().tableShown());
            assertTrue("An unchanged workspace is not reloaded", shell.table.restored.isEmpty());
            return null;
        });
    }

    @Test public void aRunNotInSavedHistoryOrUnreadableSaysSoAndSubstitutesNothing() throws Exception {
        Shell shell = new Shell();
        VisitRef missing = new VisitRef(RunFixtures.A, "missing");
        assertTrue(shell.open(recap(missing)));
        shell.settled(missing);
        edt(() -> {
            assertFalse(shell.view().model().available());
            assertTrue(shell.view().model().unavailable(), shell.view().model().unavailable().contains("is not in this saved history"));
            return null;
        });
        shell.builds.fail = new IOException("synthetic read failure");
        assertTrue(shell.open(recap(RunFixtures.A2)));
        shell.settled(RunFixtures.A2);
        edt(() -> {
            String reason = shell.view().model().unavailable();
            assertNotNull("A failed read is not shown as an empty run", reason);
            assertTrue(reason, reason.contains("could not be read") && reason.contains("synthetic read failure"));
            return null;
        });
        shell.builds.fail = null;
        edt(() -> {
            assertTrue(shell.navigator.open(Route.to(Destination.LOOT).withVisit(RunFixtures.A2)));
            assertTrue(shell.navigator.back());
            assertTrue(shell.page.recapShown());
            return null;
        });
        await("a failed read is tried again on Back", () -> shell.view().model() != null && shell.view().model().available());
    }

    @Test public void aBackStateNeedsItsRun() {
        try { new RunsState(true, null, null, false); fail("A recap state names its run"); } catch (IllegalArgumentException expected) { }
        try { new RunsState(false, RunFixtures.A1, null, false); fail("A feed state names none"); } catch (IllegalArgumentException expected) { }
        assertNull(new RunsState(false, null, null, true).workspace());
    }
}
