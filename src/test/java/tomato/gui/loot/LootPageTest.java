package tomato.gui.loot;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import tomato.history.link.VisitRef;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * The shell's {@code loot} page (spec §6.4, P6a Task 11): the Highlights and Explore tabs, explicit navigation bringing a tab
 * forward (a hidden one too), the tab-aware route wrappers that give every Loot target one composite Back state (the front tab
 * and that tab's owner state), the {@link LootFocus} tab target, closing every tab content, and the route a Highlights strip
 * cell opens. {@code ui.tabs.loot} is isolated.
 */
public class LootPageTest {
    private static final String ORDER = CustomizableTabs.PREFIX + "loot";
    private static final String SESSION_ID = "0f0e0d0c-0b0a-4908-8706-050403020100";
    private final List<LootPage> pages = new ArrayList<>();
    private String saved;

    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); PropertiesManager.setProperties(ORDER, ""); }
    @After public void release() throws Exception {
        try { SwingUtilities.invokeAndWait(() -> { for (LootPage page : pages) page.close(); }); }
        finally { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }
    }

    private LootPage page() throws Exception { return page(new JPanel(), new JPanel()); }
    private LootPage page(JComponent highlights, JComponent explore) throws Exception {
        LootPage page = edt(() -> new LootPage(highlights, explore));
        pages.add(page);
        return page;
    }

    private static List<String> titles(JTabbedPane tabs) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }

    /** A Loot target as the navigator sees it: its state, what it opened and restored, and the tab in front at each call. */
    private static final class Target implements RouteTarget {
        final Destination destination;
        final List<Route> opened = new ArrayList<>();
        final List<Object> restored = new ArrayList<>();
        final List<LootTab> frontAtOpen = new ArrayList<>(), frontAtRestore = new ArrayList<>();
        LootPage page;
        Object state;
        int captures;
        boolean reject;
        Route redirect;
        Target(Destination destination, Object state) { this.destination = destination; this.state = state; }
        @Override public Destination destination() { return destination; }
        @Override public boolean accepts(Route route) { return route.destination == destination && route.payload == null; }
        @Override public Route redirect(Route route) { return redirect; }
        @Override public Object captureState() { captures++; return state; }
        @Override public void open(Route route) {
            if (page != null) frontAtOpen.add(page.selectedTab());
            if (reject) throw new IllegalArgumentException("rejected");
            opened.add(route);
        }
        @Override public void restoreState(Object value) { if (page != null) frontAtRestore.add(page.selectedTab()); restored.add(value); }
    }

    /** Tab content with a lifecycle, as LootHighlights and the Loot workspace are. */
    private static final class Closing extends JPanel implements AutoCloseable {
        int closed;
        RuntimeException failure;
        @Override public void close() { closed++; if (failure != null) throw failure; }
    }

    @Test public void theTwoTabsFollowLootTabOrderAndHighlightsShowsFirst() throws Exception {
        assertEquals(Arrays.asList(LootTab.HIGHLIGHTS, LootTab.EXPLORE), Arrays.asList(LootTab.values()));
        assertEquals(Arrays.asList("highlights", "explore"), Arrays.asList(LootTab.HIGHLIGHTS.id(), LootTab.EXPLORE.id()));
        assertEquals(Arrays.asList("Highlights", "Explore"), Arrays.asList(LootTab.HIGHLIGHTS.title(), LootTab.EXPLORE.title()));
        assertSame(LootTab.EXPLORE, LootTab.of("explore"));
        assertNull("An unknown id is no tab", LootTab.of("live"));
        assertNull(LootTab.of(null));
        JPanel highlights = new JPanel(), explore = new JPanel();
        LootPage page = page(highlights, explore);
        edt(() -> {
            assertEquals("loot-page", page.getName());
            JTabbedPane tabs = page.tabs().component();
            assertEquals("loot-tabs", tabs.getName());
            assertSame(tabs, page.getComponent(0));
            assertEquals(Arrays.asList("Highlights", "Explore"), titles(tabs));
            assertSame(highlights, tabs.getComponentAt(0));
            assertSame(explore, tabs.getComponentAt(1));
            assertEquals(LootTab.HIGHLIGHTS, page.selectedTab());
            assertEquals("Neither tab is Analyst-only", Arrays.asList("highlights", "explore"), page.tabs().visibleIds());
            assertSame("Hierarchy walkers reach the tabs, hidden ones too", page.tabs(), tabs.getClientProperty(CustomizableTabs.class));
            assertEquals("Building the page writes no tab preference", "", PropertiesManager.getProperty(ORDER));
            page.tabs().select(LootTab.EXPLORE.id());
            assertEquals(LootTab.EXPLORE, page.selectedTab());
            assertEquals("Choosing a tab persists no selection", "", PropertiesManager.getProperty(ORDER));
            return null;
        });
        LootPage again = page();
        edt(() -> { assertEquals("A new page opens on its first visible tab again", LootTab.HIGHLIGHTS, again.selectedTab()); return null; });
    }

    @Test public void constructionNeverShowsAHiddenTabAndOpensOnTheFirstVisibleTab() throws Exception {
        PropertiesManager.setProperties(ORDER, "explore,highlights|");
        LootPage moved = page();
        edt(() -> {
            assertEquals(Arrays.asList("Explore", "Highlights"), titles(moved.tabs().component()));
            assertEquals("The page opens on its first visible tab; no selected tab is persisted", LootTab.EXPLORE, moved.selectedTab());
            return null;
        });
        PropertiesManager.setProperties(ORDER, "highlights,explore|highlights");
        LootPage hidden = page();
        edt(() -> {
            assertEquals(Collections.singletonList("Explore"), titles(hidden.tabs().component()));
            assertEquals(LootTab.EXPLORE, hidden.selectedTab());
            assertEquals(Collections.singleton("highlights"), hidden.tabs().hiddenIds());
            assertEquals("Startup only selects: the saved hidden set is untouched", "highlights,explore|highlights", PropertiesManager.getProperty(ORDER));
            return null;
        });
    }

    @Test public void bringShowsAHiddenTab() throws Exception {
        PropertiesManager.setProperties(ORDER, "highlights,explore|explore");
        LootPage page = page();
        edt(() -> {
            assertEquals(Collections.singletonList("Highlights"), titles(page.tabs().component()));
            page.bring(LootTab.EXPLORE);
            assertEquals("Explicit navigation shows a hidden tab", LootTab.EXPLORE, page.selectedTab());
            assertEquals(Arrays.asList("Highlights", "Explore"), titles(page.tabs().component()));
            assertTrue(page.tabs().hiddenIds().isEmpty());
            page.bring(LootTab.HIGHLIGHTS);
            assertEquals(LootTab.HIGHLIGHTS, page.selectedTab());
            try { page.bring(null); fail(); } catch (NullPointerException expected) { }
            return null;
        });
    }

    @Test public void theWrapperBringsItsTabCapturesTheFrontTabsOwnerAndRestoresTheTabFirst() throws Exception {
        LootPage page = page();
        Target archive = new Target(Destination.LOOT, "explore-1"), visits = new Target(Destination.LOOT, "visits-inner");
        archive.page = page; visits.page = page;
        edt(() -> {
            page.owner(LootTab.EXPLORE, archive);
            RouteTarget routes = page.routes(LootTab.EXPLORE, visits);
            assertEquals(Destination.LOOT, routes.destination());
            assertTrue(routes.accepts(Route.to(Destination.LOOT)));
            assertFalse("accepts is the inner target's", routes.accepts(Route.to(Destination.LOOT).withPayload("x")));
            assertNull(routes.redirect(Route.to(Destination.LOOT)));
            visits.redirect = Route.to(Destination.RUNS);
            assertSame("…and so is redirect", visits.redirect, routes.redirect(Route.to(Destination.LOOT)));
            visits.redirect = null;

            LootPage.PageState fromHighlights = (LootPage.PageState) routes.captureState();
            assertEquals("Highlights has no owner: the tab alone comes back", new LootPage.PageState(LootTab.HIGHLIGHTS, null), fromHighlights);
            assertEquals("Only the front tab's owner is captured", 0, archive.captures);
            assertEquals("The wrapped target's own state is not the page's", 0, visits.captures);

            Route route = Route.to(Destination.LOOT).withVisit(new VisitRef(SESSION_ID, "journal:1"));
            routes.open(route);
            assertEquals(LootTab.EXPLORE, page.selectedTab());
            assertEquals(List.of(route), visits.opened);
            assertEquals("The tab comes forward before the inner target opens", List.of(LootTab.EXPLORE), visits.frontAtOpen);
            assertEquals(new LootPage.PageState(LootTab.EXPLORE, "explore-1"), routes.captureState());

            routes.restoreState(fromHighlights);
            assertEquals("Back returns to the tab left", LootTab.HIGHLIGHTS, page.selectedTab());
            assertTrue("Nothing to restore without an owner state", archive.restored.isEmpty());

            routes.restoreState(new LootPage.PageState(LootTab.EXPLORE, "explore-0"));
            assertEquals(LootTab.EXPLORE, page.selectedTab());
            assertEquals(List.of("explore-0"), archive.restored);
            assertEquals("Back brings the tab forward first, then restores its owner", List.of(LootTab.EXPLORE), archive.frontAtRestore);

            page.bring(LootTab.HIGHLIGHTS);
            assertTrue(page.tabs().hide("explore"));
            routes.restoreState(new LootPage.PageState(LootTab.EXPLORE, "explore-2"));
            assertEquals("Back is explicit navigation: it shows a tab hidden since", LootTab.EXPLORE, page.selectedTab());
            assertEquals(List.of("explore-0", "explore-2"), archive.restored);
            try { routes.restoreState("foreign"); fail("A foreign state is rejected"); } catch (IllegalArgumentException expected) { }
            try { page.owner(LootTab.EXPLORE, routes); fail("A page target is never an owner: its capture would recurse"); }
            catch (IllegalArgumentException expected) { }
            page.owner(LootTab.EXPLORE, null);
            assertEquals("Without an owner the tab alone is captured", new LootPage.PageState(LootTab.EXPLORE, null), routes.captureState());
            return null;
        });
    }

    @Test public void aFailedOpenBringsBackThePreviousTabAndHidesTheTabAgain() throws Exception {
        PropertiesManager.setProperties(ORDER, "highlights,explore|explore");
        LootPage page = page();
        Target visits = new Target(Destination.LOOT, null);
        visits.reject = true;
        edt(() -> {
            RouteTarget routes = page.routes(LootTab.EXPLORE, visits);
            try { routes.open(Route.to(Destination.LOOT)); fail("The rejection reaches the navigator"); }
            catch (IllegalArgumentException expected) { }
            assertEquals("A rejected route changes no tab", LootTab.HIGHLIGHTS, page.selectedTab());
            assertEquals(Collections.singleton("explore"), page.tabs().hiddenIds());
            assertEquals(Collections.singletonList("Highlights"), titles(page.tabs().component()));
            assertEquals("…nor the saved hidden set", "highlights,explore|explore", PropertiesManager.getProperty(ORDER));

            page.bring(LootTab.EXPLORE);
            page.bring(LootTab.HIGHLIGHTS);
            try { routes.open(Route.to(Destination.LOOT)); fail(); } catch (IllegalArgumentException expected) { }
            assertEquals(LootTab.HIGHLIGHTS, page.selectedTab());
            assertTrue("A tab shown before stays shown", page.tabs().hiddenIds().isEmpty());
            return null;
        });
    }

    @Test public void theTabTargetTakesLootFocusRoutesOnly() throws Exception {
        try { new LootFocus(null); fail(); } catch (NullPointerException expected) { }
        assertEquals(new LootFocus(LootTab.EXPLORE), new LootFocus(LootTab.EXPLORE));
        LootPage page = page();
        Target archive = new Target(Destination.LOOT, "explore-1");
        edt(() -> {
            page.owner(LootTab.EXPLORE, archive);
            RouteTarget target = page.tabTarget();
            Route explore = Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.EXPLORE));
            Route highlights = Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.HIGHLIGHTS));
            assertEquals(Destination.LOOT, target.destination());
            assertTrue(target.accepts(explore));
            assertTrue(target.accepts(highlights));
            assertFalse("A plain Loot route is the workspace's", target.accepts(Route.to(Destination.LOOT)));
            assertFalse(target.accepts(Route.to(Destination.LOOT).withPayload("explore")));
            assertFalse(target.accepts(Route.to(Destination.RUNS).withPayload(new LootFocus(LootTab.EXPLORE))));
            assertFalse("A tab route carries nothing else", target.accepts(explore.withVisit(new VisitRef(SESSION_ID, "journal:1"))));
            assertFalse(target.accepts(explore.withQuery(LootQuery.initial(LootQuery.View.ITEMS, ArchiveQuery.CURRENT))));
            assertFalse(target.accepts(explore.withBounds(1L, 2L)));
            assertFalse(target.accepts(explore.withRecording("rec-1", null)));
            try { target.open(Route.to(Destination.LOOT)); fail("Unsupported routes are rejected"); } catch (IllegalArgumentException expected) { }

            Object before = target.captureState();
            assertEquals(new LootPage.PageState(LootTab.HIGHLIGHTS, null), before);
            target.open(explore);
            assertEquals(LootTab.EXPLORE, page.selectedTab());
            assertTrue("A tab route changes nothing in the tab", archive.opened.isEmpty() && archive.restored.isEmpty());
            assertEquals(new LootPage.PageState(LootTab.EXPLORE, "explore-1"), target.captureState());
            target.open(highlights);
            assertEquals(LootTab.HIGHLIGHTS, page.selectedTab());
            target.open(explore);
            target.restoreState(before);
            assertEquals("The tab target restores the same composite state", LootTab.HIGHLIGHTS, page.selectedTab());
            return null;
        });
    }

    /**
     * P6b Task 9 (R3 B10, decision 6): a focus may carry Home's window. Opening it brings Highlights forward and applies the window
     * as a click on its choice does (selected and kept in {@code ui.loot.highlights}); a focus without one keeps the window shown.
     * A window belongs to Highlights only. Highlights' window is its own preference, so Back restores the tab only.
     */
    @Test public void aFocusWithAWindowOpensHighlightsOnItAndKeepsIt() throws Exception {
        assertNull("The tab-only focus keeps the window", new LootFocus(LootTab.HIGHLIGHTS).window());
        assertEquals(new LootFocus(LootTab.HIGHLIGHTS, null), new LootFocus(LootTab.HIGHLIGHTS));
        assertEquals(HighlightsModel.Window.SESSION, new LootFocus(LootTab.HIGHLIGHTS, HighlightsModel.Window.SESSION).window());
        try { new LootFocus(LootTab.EXPLORE, HighlightsModel.Window.TODAY); fail("A window is Highlights' only"); } catch (IllegalArgumentException expected) { }
        Map<String, String> prefs = new HashMap<>();
        List<String> writes = new ArrayList<>();
        LootHighlightsTest.Fake reader = new LootHighlightsTest.Fake(LootHighlightsTest::populated);
        LootHighlights highlights = edt(() -> new LootHighlights(reader, ZoneId.of("America/New_York"), prefs::get,
            (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }, 0, 0));
        LootPage page = page(highlights, new JPanel());
        edt(() -> {
            page.bring(LootTab.EXPLORE);
            RouteTarget target = page.tabTarget();
            Object before = target.captureState();
            Route session = Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.HIGHLIGHTS, HighlightsModel.Window.SESSION));
            assertTrue(target.accepts(session));
            target.open(session);
            assertEquals(LootTab.HIGHLIGHTS, page.selectedTab());
            assertEquals("Opened on This session", HighlightsModel.Window.SESSION, highlights.window());
            assertEquals("…and kept, as a click keeps it", List.of("ui.loot.highlights=session"), writes);
            target.open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.HIGHLIGHTS)));
            assertEquals("No window keeps the one shown", HighlightsModel.Window.SESSION, highlights.window());
            target.open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.HIGHLIGHTS, HighlightsModel.Window.TODAY)));
            assertEquals(HighlightsModel.Window.TODAY, highlights.window());
            assertEquals(List.of("ui.loot.highlights=session", "ui.loot.highlights=today"), writes);
            target.restoreState(before);
            assertEquals("Back brings the tab back", LootTab.EXPLORE, page.selectedTab());
            assertEquals("…and leaves Highlights' own choice", HighlightsModel.Window.TODAY, highlights.window());
            return null;
        });
        assertEquals("Never shown: nothing was read", 0, reader.reads.get());
        LootPage plain = page();
        edt(() -> {
            plain.tabTarget().open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.HIGHLIGHTS, HighlightsModel.Window.SESSION)));
            assertEquals("A Highlights tab of another kind just comes forward", LootTab.HIGHLIGHTS, plain.selectedTab());
            return null;
        });
    }

    @Test public void closeReachesEveryTabContentIncludingAHiddenTabOnce() throws Exception {
        PropertiesManager.setProperties(ORDER, "highlights,explore|highlights");
        Closing highlights = new Closing(), explore = new Closing();
        LootPage page = page(highlights, explore);
        edt(() -> {
            assertEquals(Collections.singletonList("explore"), page.tabs().visibleIds());
            assertNull("The hidden Highlights tab is detached", highlights.getParent());
            explore.failure = new IllegalStateException("explore failed");
            try { page.close(); fail("A failed close is reported"); }
            catch (IllegalStateException expected) { assertEquals("explore failed", expected.getMessage()); }
            assertEquals("A hidden Highlights tab is closed", 1, highlights.closed);
            assertEquals(1, explore.closed);
            page.close();
            assertEquals("Closing is idempotent", 1, highlights.closed);
            assertEquals(1, explore.closed);
            return null;
        });
        LootHighlights real = edt(() -> new LootHighlights(new LootHighlights.Reader() {
            @Override public HighlightsModel read(HighlightsModel.Window window, tomato.history.archive.Cancellation cancel) { throw new AssertionError("Never shown, never read"); }
            @Override public long revision() { return 0; }
        }, ZoneId.of("UTC"), key -> null, (key, value) -> { }, 0, 0));
        LootPage withHighlights = page(real, new JPanel());
        edt(() -> {
            withHighlights.close();
            real.request();
            assertFalse("Closing the page closes Loot highlights: a later request reads nothing", real.loading());
            return null;
        });
    }

    @Test public void aStripCellOpensExploreFilteredToItsDungeonOverTheHighlightsWindow() throws Exception {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        long now = LocalDate.of(2026, 9, 29).atTime(15, 30).atZone(zone).toInstant().toEpochMilli();
        long from = LocalDate.of(2026, 9, 29).atStartOfDay(zone).toInstant().toEpochMilli();
        long until = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli();

        Route unknown = LootPage.dungeonRoute(null, HighlightsModel.Window.TODAY, zone, now);
        assertEquals("Unknown area opens Explore unfiltered", Destination.LOOT, unknown.destination);
        assertEquals(new LootFocus(LootTab.EXPLORE), unknown.payload);
        assertNull(unknown.query);

        Route today = LootPage.dungeonRoute("Lost Halls", HighlightsModel.Window.TODAY, zone, now);
        assertEquals(Destination.LOOT, today.destination);
        assertNull("A query route: the Loot workspace's targets open it", today.payload);
        assertNull(today.visit);
        @SuppressWarnings("unchecked") ArchiveQuery<LootQuery.Facets, LootQuery.Sort> query = (ArchiveQuery<LootQuery.Facets, LootQuery.Sort>) today.query;
        assertEquals("Today reads every saved session…", SessionStore.ALL, query.scope());
        assertEquals("…kept to the local day, as Highlights counts it", Long.valueOf(from), query.bounds().from);
        assertEquals(Long.valueOf(until), query.bounds().until);
        assertEquals(zone.getId(), query.bounds().zone);
        assertFalse("Undated drops are outside the day, as in Highlights", query.bounds().includeUnknown);
        assertEquals(Collections.singleton("Lost Halls"), query.facets().dungeons);
        assertEquals("All Items, Explore's first view", LootQuery.View.ITEMS, query.facets().view);
        assertFalse(query.facets().drilled());

        Route session = LootPage.dungeonRoute("Snake Pit", HighlightsModel.Window.SESSION, zone, now);
        @SuppressWarnings("unchecked") ArchiveQuery<LootQuery.Facets, LootQuery.Sort> current = (ArchiveQuery<LootQuery.Facets, LootQuery.Sort>) session.query;
        assertEquals("This session is the current session", ArchiveQuery.CURRENT, current.scope());
        assertNull(current.bounds().from);
        assertNull(current.bounds().until);
        assertEquals(Collections.singleton("Snake Pit"), current.facets().dungeons);
        assertEquals(LootQuery.View.ITEMS, current.facets().view);
    }

    interface Checked<T> { T get() throws Exception; }
    static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
}
