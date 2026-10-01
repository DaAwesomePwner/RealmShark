package tomato.gui.loot.explore;

import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunFixtures;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.RunsLevelTest.edt;
import static tomato.gui.loot.explore.RunsLevelTest.named;

/** Explore's Pictures | Table switch, its remembered choice, the routes it wraps and closing. */
public class LootExplorePageTest {
    private final Map<String, String> prefs = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final List<LootExplorePage> pages = new ArrayList<>();

    @After public void release() throws Exception { edt(() -> { for (LootExplorePage page : pages) page.close(); return null; }); }

    /** A workspace stand-in that counts closes. */
    static final class Table extends JPanel implements AutoCloseable {
        int closes;
        @Override public void close() { closes++; }
    }

    /** A Loot target that records what it opened and restored, and can reject routes. */
    static final class Target implements RouteTarget {
        final List<Route> opened = new ArrayList<>();
        Object state = "inner-1", restored;
        RuntimeException fail;
        @Override public Destination destination() { return Destination.LOOT; }
        @Override public Object captureState() { return state; }
        @Override public void open(Route route) { if (fail != null) throw fail; opened.add(route); }
        @Override public void restoreState(Object value) { restored = value; }
    }

    private LootExplorePage page(JComponent table) throws Exception {
        LootCatalog.Reader catalog = cancel -> java.util.List.of(CollectionModelTest.bag(100, CollectionModelTest.ut(1, 2)));
        LootExplorePage page = edt(() -> new LootExplorePage(table, new ExplorePictures(
                new RunsLevel(RunFeedView.picker(() -> null), new RunsLevelTest.FakeLoader(), Runnable::run),
                new CollectionLevel(catalog, Runnable::run, id -> "Synthetic item " + id),
                new ItemLevel(catalog, Runnable::run, java.time.ZoneId.of("UTC")), id -> "Synthetic item " + id, key -> null, (key, value) -> { }),
            prefs::get, (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }));
        pages.add(page);
        return page;
    }

    @Test public void picturesShowFirstAndTheChoiceIsRemembered() throws Exception {
        LootExplorePage page = page(new Table());
        edt(() -> {
            assertFalse("Explore opens on Pictures", page.tableShown());
            assertTrue("Restoring the default writes nothing", writes.isEmpty());
            named(page, "loot-explore-view-1", AbstractButton.class).doClick();
            assertTrue(page.tableShown());
            assertEquals(List.of(LootExplorePage.VIEW_KEY + "=table"), writes);
            return null;
        });
        LootExplorePage again = page(new Table());
        edt(() -> {
            assertTrue("The choice is remembered", again.tableShown());
            assertEquals("Restoring writes nothing", 1, writes.size());
            return null;
        });
    }

    @Test public void routesBringTheViewTheyNeedAndBackRestoresIt() throws Exception {
        LootExplorePage page = page(new Table());
        Target target = new Target();
        edt(() -> {
            RouteTarget routes = page.routes(target);
            page.showTable();
            routes.open(Route.to(Destination.LOOT).withVisit(RunFixtures.A1));
            assertFalse("An exact run opens Pictures", page.tableShown());
            assertEquals(RunFixtures.A1, page.pictures().selectedRun());
            assertTrue("The workspace is left as it was", target.opened.isEmpty());
            Object back = routes.captureState();

            ArchiveQuery<LootQuery.Facets, LootQuery.Sort> occurrences = LootQuery.initial(LootQuery.View.OCCURRENCES, SessionStore.ALL);
            Route query = Route.to(Destination.LOOT).withQuery(occurrences);
            routes.open(query);
            assertTrue("A query opens Table", page.tableShown());
            assertEquals(List.of(query), target.opened);

            routes.restoreState(back);
            assertFalse("Back returns to Pictures", page.tableShown());
            assertEquals(RunFixtures.A1, page.pictures().selectedRun());
            assertEquals("inner-1", target.restored);

            target.fail = new IllegalArgumentException("rejected");
            try { routes.open(query); fail("The target rejected the route"); } catch (IllegalArgumentException expected) { }
            assertFalse("A rejected route leaves Pictures showing", page.tableShown());
            return null;
        });
    }

    @Test public void backRestoresLootOutsideRuns() throws Exception {
        LootExplorePage page = page(new Table());
        Target target = new Target();
        RouteTarget routes = edt(() -> page.routes(target));
        edt(() -> { page.pictures().openUnlinked(); return null; });
        edt(() -> null);
        Object back = edt(routes::captureState);
        edt(() -> {
            routes.open(Route.to(Destination.LOOT).withVisit(RunFixtures.A1));
            assertFalse(page.pictures().showingUnlinked());
            assertEquals(RunFixtures.A1, page.pictures().selectedRun());
            routes.restoreState(back);
            assertTrue(page.pictures().showingUnlinked());
            assertNull(page.pictures().selectedRun());
            assertFalse(page.tableShown());
            assertEquals("inner-1", target.restored);
            return null;
        });
        edt(() -> null);
        assertTrue(edt(() -> page.pictures().showingUnlinked()));
        assertEquals(RunsLevel.NO_UNLINKED, edt(() -> page.pictures().runs().status().getText()));
    }

    @Test public void anItemRouteOpensTheItemLevelAndBackReturns() throws Exception {
        LootExplorePage page = page(new Table());
        edt(() -> {
            RouteTarget items = page.itemTarget();
            page.showTable();
            Object back = items.captureState();
            Route route = Route.to(Destination.LOOT).withPayload(new LootExplorePage.ExploreItem(1));
            assertTrue(items.accepts(route));
            assertFalse("Other Loot routes are not the item target's", items.accepts(Route.to(Destination.LOOT)));
            items.open(route);
            assertFalse(page.tableShown());
            assertEquals(ExplorePictures.Level.ITEM, page.pictures().level());
            assertEquals(1, page.pictures().itemId());
            items.restoreState(back);
            assertTrue("Back returns to Table", page.tableShown());
            assertEquals(ExplorePictures.Level.RUNS, page.pictures().level());
            return null;
        });
    }

    @Test public void closingReleasesThePicturesAndTheWorkspaceOnce() throws Exception {
        Table table = new Table();
        LootExplorePage page = page(table);
        edt(() -> { page.close(); page.close(); return null; });
        assertEquals(1, table.closes);
    }
}
