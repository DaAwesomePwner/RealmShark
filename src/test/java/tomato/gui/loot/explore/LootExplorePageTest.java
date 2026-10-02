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
import tomato.gui.loot.LootFocus;
import tomato.gui.loot.LootTab;
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
                new DungeonsLevel(cancel -> List.of(AtlasModelTest.card("Lost Halls", 1, 100)), catalog, Runnable::run),
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

    @Test public void focusTargetRejectsOtherPayloadsAndRestoresPicturesAcrossBackAndForward() throws Exception {
        LootExplorePage page = page(new Table());
        edt(() -> {
            RouteTarget focus = page.focusTarget(), items = page.itemTarget();
            assertFalse(focus.accepts(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.EXPLORE))));
            assertFalse(focus.accepts(Route.to(Destination.LOOT).withPayload(new LootExplorePage.ExploreItem(1))));
            assertFalse(focus.accepts(Route.to(Destination.LOOT)));
            assertFalse(focus.accepts(Route.to(Destination.RUN_RECAP).withPayload(new ExplorePictures.Focus(ExplorePictures.Level.RUNS, null))));
            assertFalse(items.accepts(Route.to(Destination.LOOT).withPayload(new ExplorePictures.Focus(ExplorePictures.Level.DUNGEONS, null))));

            page.onNavigate(target -> focus.open(Route.to(Destination.LOOT).withPayload(target)));
            page.pictures().openRun(RunFixtures.A2);
            List<Object> history = new ArrayList<>();
            List<ExplorePictures.State> expected = new ArrayList<>();
            history.add(focus.captureState()); expected.add(page.pictures().state());
            named(page, "loot-explore-entry-1", AbstractButton.class).doClick();
            history.add(focus.captureState()); expected.add(page.pictures().state());
            Route dungeon = Route.to(Destination.LOOT).withPayload(new ExplorePictures.Focus(ExplorePictures.Level.RUNS, "Lost Halls"));
            assertTrue(focus.accepts(dungeon));
            focus.open(dungeon);
            page.pictures().runs().openRun(RunFixtures.A1); // the strip's pick is not another route
            history.add(focus.captureState()); expected.add(page.pictures().state());
            Route collection = Route.to(Destination.LOOT).withPayload(new ExplorePictures.Focus(ExplorePictures.Level.COLLECTION, "Lost Halls"));
            focus.open(collection);
            history.add(focus.captureState()); expected.add(page.pictures().state());
            Route item = Route.to(Destination.LOOT).withPayload(new LootExplorePage.ExploreItem(1));
            items.open(item);
            history.add(items.captureState()); expected.add(page.pictures().state());
            assertEquals(List.of(ExplorePictures.Level.RUNS, ExplorePictures.Level.DUNGEONS, ExplorePictures.Level.RUNS,
                ExplorePictures.Level.COLLECTION, ExplorePictures.Level.ITEM), expected.stream().map(ExplorePictures.State::level).toList());

            for (int i = history.size() - 1; i >= 0; i--) {
                focus.restoreState(history.get(i));
                assertEquals("Back step " + i, expected.get(i), page.pictures().state());
                assertFalse(page.tableShown());
                assertFilter(page.pictures());
            }
            for (int i = 0; i < history.size(); i++) {
                items.restoreState(history.get(i));
                assertEquals("Forward step " + i, expected.get(i), page.pictures().state());
                assertFilter(page.pictures());
            }
            focus.open(dungeon);
            assertEquals("Lost Halls", page.pictures().runs().feed().query().map());
            focus.open(collection);
            assertEquals("Lost Halls", page.pictures().collection().dungeonFilter());
            items.open(item);
            assertEquals(ExplorePictures.Level.ITEM, page.pictures().level());
            assertEquals("Lost Halls", page.pictures().dungeon());

            page.showTable();
            Object table = focus.captureState();
            focus.open(dungeon);
            assertFalse(page.tableShown());
            focus.restoreState(table);
            assertTrue(page.tableShown());
            assertEquals(ExplorePictures.Level.ITEM, page.pictures().level());
            return null;
        });
    }

    private static void assertFilter(ExplorePictures pictures) {
        ExplorePictures.State state = pictures.state();
        ExplorePictures.Level root = state.level() == ExplorePictures.Level.ITEM ? state.itemFrom() : state.level();
        if (root == ExplorePictures.Level.RUNS) assertEquals(state.dungeon(), pictures.runs().feed().query().map());
        if (root == ExplorePictures.Level.COLLECTION) assertEquals(state.dungeon(), pictures.collection().dungeonFilter());
    }
}
