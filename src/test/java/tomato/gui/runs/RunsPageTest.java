package tomato.gui.runs;

import java.util.*;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.history.archive.ArchiveQuery;
import tomato.gui.activity.ActivityQueries;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.runs.RunFeedViewTest.edt;

/** Page 10: the feed and recap slots, and the archive's row routes bringing the Table view forward with Back restoring the view. */
public class RunsPageTest {
    private final Map<String, String> prefs = new HashMap<>();
    private final List<RunsPage> pages = new ArrayList<>();

    @After public void release() throws Exception { SwingUtilities.invokeAndWait(() -> { for (RunsPage page : pages) page.close(); }); }

    private RunsPage page(JComponent workspace) throws Exception {
        RunsPage page = edt(() -> new RunsPage(new RunFeedView(workspace, () -> null, HomeHistoryFixture.ZONE,
            new DisplayModeModel(prefs::get, prefs::put), prefs::get, prefs::put)));
        pages.add(page);
        return page;
    }

    /** The workspace's route target as the navigator sees it: records what it was asked to open and restore. */
    private static final class Target implements RouteTarget {
        final List<Route> opened = new ArrayList<>();
        final List<Object> restored = new ArrayList<>();
        Object state = "workspace-state-1";
        boolean reject;
        @Override public Destination destination() { return Destination.RUNS; }
        @Override public Object captureState() { return state; }
        @Override public void open(Route route) { if (reject) throw new IllegalArgumentException("rejected"); opened.add(route); }
        @Override public void restoreState(Object value) { restored.add(value); }
    }

    @Test public void theFeedShowsFirstAndTheRecapSlotShowsWhenFilled() throws Exception {
        JPanel workspace = new JPanel();
        RunsPage page = page(workspace);
        edt(() -> {
            assertEquals("runs-page", page.getName());
            assertSame(workspace, page.workspace());
            assertSame(workspace, page.feed().table());
            assertTrue(page.feed().isVisible());
            assertFalse(page.recapShown());
            page.showRecap();
            assertFalse("An empty slot never shows", page.recapShown());
            JLabel recap = new JLabel("Recap");
            page.setRecap(recap);
            assertSame(recap, page.recap());
            assertFalse("Filling the slot does not show it", page.recapShown());
            page.showRecap();
            assertTrue(page.recapShown());
            assertTrue(recap.isShowing() || recap.getParent().isVisible());
            assertFalse(page.feed().isVisible());
            page.showTable();
            assertFalse("A row route leaves the recap for the Table view", page.recapShown());
            assertTrue(page.feed().isVisible());
            assertTrue(page.feed().tableShown());
            page.showCards();
            assertFalse(page.feed().tableShown());
            page.setRecap(null);
            assertNull(page.recap());
            return null;
        });
    }

    @Test public void rowRoutesBringTheTableViewForwardAndBackRestoresTheViewWithTheWorkspaceState() throws Exception {
        RunsPage page = page(new JPanel());
        Target target = new Target();
        RouteTarget routes = edt(() -> page.tableRoutes(target));
        VisitRef visit = new VisitRef(HomeHistoryFixture.MORNING, "c1");
        edt(() -> {
            assertEquals(Destination.RUNS, routes.destination());
            assertTrue(routes.accepts(Route.to(Destination.RUNS)));
            Object cards = routes.captureState();
            assertFalse(page.feed().tableShown());
            routes.open(Route.to(Destination.RUNS));
            assertFalse("A plain Runs route leaves the view as it is", page.feed().tableShown());
            routes.open(Route.to(Destination.RUNS).withVisit(visit));
            assertTrue("An exact visit opens the Table view on its row", page.feed().tableShown());
            assertEquals(visit, target.opened.get(1).visit);
            assertEquals("table", prefs.get(RunFeedView.VIEW_KEY));
            page.showCards();
            routes.open(Route.to(Destination.RUNS).withQuery(ActivityQueries.initial().withScope(ArchiveQuery.CURRENT)));
            assertTrue("A query route shows the table too", page.feed().tableShown());

            target.state = "workspace-state-2";
            Object table = routes.captureState();
            routes.restoreState(cards);
            assertFalse("Back restores the Cards view", page.feed().tableShown());
            assertEquals("workspace-state-1", target.restored.get(0));
            routes.restoreState(table);
            assertTrue("…or the Table view", page.feed().tableShown());
            assertEquals("workspace-state-2", target.restored.get(1));
            try { routes.restoreState("foreign"); fail("A foreign state is rejected"); } catch (IllegalArgumentException expected) { }

            page.showCards();
            target.reject = true;
            try { routes.open(Route.to(Destination.RUNS).withVisit(visit)); fail("The rejection reaches the navigator"); }
            catch (IllegalArgumentException expected) { }
            assertFalse("A rejected route changes no view", page.feed().tableShown());
            return null;
        });
    }
}
