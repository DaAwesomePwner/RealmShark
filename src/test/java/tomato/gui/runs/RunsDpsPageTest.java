package tomato.gui.runs;

import java.util.*;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.history.link.VisitRef;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.runs.RunFeedViewTest.edt;

/**
 * Page 10, Runs & DPS: the five tabs, explicit navigation bringing a tab forward (even a hidden one), and the tab-aware route
 * wrappers that give every page-10 target one composite Back state (the front tab and that tab's owner state).
 */
public class RunsDpsPageTest {
    private static final String ORDER = CustomizableTabs.PREFIX + "runs";
    private final Map<String, String> prefs = new HashMap<>();
    private final List<RunsDpsPage> pages = new ArrayList<>();
    private String saved;

    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); PropertiesManager.setProperties(ORDER, ""); }
    @After public void release() throws Exception {
        try { SwingUtilities.invokeAndWait(() -> { for (RunsDpsPage page : pages) page.close(); }); }
        finally { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }
    }

    private RunsDpsPage page() throws Exception { return page(new JPanel()); }
    private RunsDpsPage page(JComponent liveMeter) throws Exception {
        RunsDpsPage page = edt(() -> new RunsDpsPage(new RunsPage(new RunFeedView(new JPanel(), () -> null, HomeHistoryFixture.ZONE,
            new DisplayModeModel(prefs::get, prefs::put), prefs::get, prefs::put)), liveMeter));
        pages.add(page);
        return page;
    }

    private static List<String> titles(JTabbedPane tabs) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }

    /** A page-10 target as the navigator sees it: its state, what it opened and restored, and the tab in front at each call. */
    private static final class Target implements RouteTarget {
        final Destination destination;
        final List<Route> opened = new ArrayList<>();
        final List<Object> restored = new ArrayList<>();
        final List<RunsTab> frontAtOpen = new ArrayList<>(), frontAtRestore = new ArrayList<>();
        RunsDpsPage page;
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

    /** Tab content with a lifecycle, as a DungeonsView or a workspace is. */
    private static final class Closing extends JPanel implements AutoCloseable {
        int closed;
        RuntimeException failure;
        @Override public void close() { closed++; if (failure != null) throw failure; }
    }

    @Test public void theFiveTabsFollowRunsTabOrderAndTheFeedShowsFirst() throws Exception {
        assertEquals(Arrays.asList(RunsTab.FEED, RunsTab.DUNGEONS, RunsTab.LIVE_METER, RunsTab.RESOURCES, RunsTab.RECORDINGS), Arrays.asList(RunsTab.values()));
        assertEquals(Arrays.asList("feed", "dungeons", "live-meter", "resources", "recordings"),
            Arrays.asList(RunsTab.FEED.id(), RunsTab.DUNGEONS.id(), RunsTab.LIVE_METER.id(), RunsTab.RESOURCES.id(), RunsTab.RECORDINGS.id()));
        assertEquals("Live meter", RunsTab.LIVE_METER.title());
        assertSame(RunsTab.RECORDINGS, RunsTab.of("recordings"));
        assertNull("An unknown id is no tab", RunsTab.of("meters"));
        assertNull(RunsTab.of(null));
        JPanel meter = new JPanel();
        RunsDpsPage page = page(meter);
        edt(() -> {
            assertEquals("runs-dps-page", page.getName());
            JTabbedPane tabs = page.tabs().component();
            assertEquals("runs-tabs", tabs.getName());
            assertSame(tabs, page.getComponent(0));
            assertEquals(Arrays.asList("Feed", "Dungeons", "Live meter", "Resources & buffs", "Recordings"), titles(tabs));
            assertSame("The Feed is the Runs page, unchanged", page.feed(), tabs.getComponentAt(0));
            assertEquals("runs-page", page.feed().getName());
            assertSame(meter, tabs.getComponentAt(2));
            assertEquals(RunsTab.FEED, page.selectedTab());
            assertEquals("No tab is Analyst-only", Arrays.asList("feed", "dungeons", "live-meter", "resources", "recordings"), page.tabs().visibleIds());
            assertSame("Hierarchy walkers reach the tabs, hidden ones too", page.tabs(), tabs.getClientProperty(CustomizableTabs.class));
            assertEquals("Building the page writes no tab preference", "", PropertiesManager.getProperty(ORDER));
            return null;
        });
    }

    @Test public void constructionNeverShowsAHiddenTabAndOpensOnTheFirstVisibleTab() throws Exception {
        PropertiesManager.setProperties(ORDER, "live-meter,feed,dungeons,recordings|");
        RunsDpsPage moved = page();
        edt(() -> {
            assertEquals(Arrays.asList("Live meter", "Resources & buffs", "Feed", "Dungeons", "Recordings"), titles(moved.tabs().component()));
            assertEquals("The page opens on its first visible tab; no selected tab is persisted", RunsTab.LIVE_METER, moved.selectedTab());
            assertEquals("Adding Resources in memory does not save the order", "live-meter,feed,dungeons,recordings|", PropertiesManager.getProperty(ORDER));
            moved.tabs().move("resources", 1);
            assertEquals("A user move saves the complete order", "live-meter,feed,resources,dungeons,recordings|", PropertiesManager.getProperty(ORDER));
            return null;
        });
        PropertiesManager.setProperties(ORDER, "recordings,feed,dungeons,live-meter|feed,live-meter");
        RunsDpsPage hidden = page();
        edt(() -> {
            assertEquals(Arrays.asList("Recordings", "Dungeons", "Resources & buffs"), titles(hidden.tabs().component()));
            assertEquals(RunsTab.RECORDINGS, hidden.selectedTab());
            assertEquals(new LinkedHashSet<>(Arrays.asList("feed", "live-meter")), hidden.tabs().hiddenIds());
            assertEquals("Startup only selects: the saved hidden set is untouched", "recordings,feed,dungeons,live-meter|feed,live-meter",
                PropertiesManager.getProperty(ORDER));
            return null;
        });
    }

    @Test public void resourcesAppendsWithoutASavedLiveMeterAndKeepsAnExistingPosition() throws Exception {
        PropertiesManager.setProperties(ORDER, "feed|dungeons");
        RunsDpsPage withoutMeter = page();
        edt(() -> {
            assertEquals(Arrays.asList("feed", "dungeons", "live-meter", "recordings", "resources"), withoutMeter.tabs().order());
            assertEquals(Arrays.asList("Feed", "Live meter", "Recordings", "Resources & buffs"), titles(withoutMeter.tabs().component()));
            assertEquals(Collections.singleton("dungeons"), withoutMeter.tabs().hiddenIds());
            assertEquals("A partial saved layout is not rewritten on read", "feed|dungeons", PropertiesManager.getProperty(ORDER));
            return null;
        });
        PropertiesManager.setProperties(ORDER, "resources,recordings,live-meter,feed,dungeons|resources");
        RunsDpsPage positioned = page();
        edt(() -> {
            assertEquals(Arrays.asList("resources", "recordings", "live-meter", "feed", "dungeons"), positioned.tabs().order());
            assertEquals(Collections.singleton("resources"), positioned.tabs().hiddenIds());
            assertEquals(RunsTab.RECORDINGS, positioned.selectedTab());
            assertEquals("An existing Resources position is left alone", "resources,recordings,live-meter,feed,dungeons|resources", PropertiesManager.getProperty(ORDER));
            return null;
        });
    }

    @Test public void resourcesIsAvailableInBothModesAndRoutesRestoreItsOwnState() throws Exception {
        RunsDpsPage page = page();
        edt(() -> {
            DisplayModeModel mode = DisplayModeModel.application();
            DisplayModeModel.Mode before = mode.mode();
            String preference = PropertiesManager.getProperty(DisplayModeModel.KEY);
            try {
                for (DisplayModeModel.Mode value : DisplayModeModel.Mode.values()) {
                    mode.set(value);
                    assertTrue("Resources is available in " + value, page.tabs().visibleIds().contains("resources"));
                }
                Target resources = new Target(Destination.RESOURCES, "resources-1");
                resources.page = page;
                page.owner(RunsTab.RESOURCES, resources);
                RouteTarget route = page.routes(RunsTab.RESOURCES, resources);
                assertTrue(page.tabs().hide("resources"));
                route.open(Route.to(Destination.RESOURCES));
                assertEquals(RunsTab.RESOURCES, page.selectedTab());
                assertEquals(List.of(RunsTab.RESOURCES), resources.frontAtOpen);
                Object state = page.tabTarget().captureState();
                assertEquals(new RunsDpsPage.PageState(RunsTab.RESOURCES, "resources-1"), state);
                page.bring(RunsTab.LIVE_METER);
                page.liveMeterTarget(() -> {}).restoreState(state);
                assertEquals(RunsTab.RESOURCES, page.selectedTab());
                assertEquals(List.of("resources-1"), resources.restored);
                assertEquals(List.of(RunsTab.RESOURCES), resources.frontAtRestore);
            } finally {
                mode.set(before);
                PropertiesManager.setProperties(DisplayModeModel.KEY, preference == null ? "" : preference);
            }
            return null;
        });
    }

    @Test public void bringShowsAHiddenTabAndSetContentFillsAHolderInPlace() throws Exception {
        PropertiesManager.setProperties(ORDER, "feed,dungeons,live-meter,recordings|live-meter");
        RunsDpsPage page = page();
        edt(() -> {
            assertEquals(Arrays.asList("Feed", "Dungeons", "Resources & buffs", "Recordings"), titles(page.tabs().component()));
            page.bring(RunsTab.LIVE_METER);
            assertEquals("Explicit navigation shows a hidden tab", RunsTab.LIVE_METER, page.selectedTab());
            assertEquals(Arrays.asList("Feed", "Dungeons", "Live meter", "Resources & buffs", "Recordings"), titles(page.tabs().component()));
            assertTrue(page.tabs().hiddenIds().isEmpty());
            page.bring(RunsTab.FEED);
            assertEquals(RunsTab.FEED, page.selectedTab());

            JTabbedPane tabs = page.tabs().component();
            JComponent holder = (JComponent) tabs.getComponentAt(1);
            JLabel first = new JLabel("Dungeons 1"), second = new JLabel("Dungeons 2");
            page.setContent(RunsTab.DUNGEONS, first);
            assertSame(holder, tabs.getComponentAt(1));
            assertSame(holder, first.getParent());
            page.setContent(RunsTab.DUNGEONS, second);
            assertSame("The tab itself is never re-added", holder, tabs.getComponentAt(1));
            assertNull(first.getParent());
            assertSame(holder, second.getParent());
            assertEquals(1, holder.getComponentCount());
            assertEquals(5, tabs.getTabCount());
            assertEquals("Filling a holder does not bring its tab forward", RunsTab.FEED, page.selectedTab());
            page.setContent(RunsTab.DUNGEONS, null);
            assertEquals(0, holder.getComponentCount());
            page.setContent(RunsTab.RECORDINGS, first);
            assertSame(tabs.getComponentAt(4), first.getParent());
            try { page.setContent(RunsTab.FEED, new JPanel()); fail("The Feed is fixed at construction"); } catch (IllegalArgumentException expected) { }
            try { page.setContent(RunsTab.LIVE_METER, new JPanel()); fail("So is the Live meter"); } catch (IllegalArgumentException expected) { }
            return null;
        });
    }

    @Test public void theWrapperBringsItsTabCapturesTheFrontTabsOwnerAndRestoresTheTabFirst() throws Exception {
        RunsDpsPage page = page();
        Target feedOwner = new Target(Destination.RUNS, "feed-1"), meterOwner = new Target(Destination.ENCOUNTER, "meter-1");
        Target resources = new Target(Destination.RESOURCES, "resources-inner");
        feedOwner.page = page; meterOwner.page = page; resources.page = page;
        edt(() -> {
            page.owner(RunsTab.FEED, feedOwner);
            page.owner(RunsTab.LIVE_METER, meterOwner);
            RouteTarget routes = page.routes(RunsTab.LIVE_METER, resources);
            assertEquals(Destination.RESOURCES, routes.destination());
            assertTrue(routes.accepts(Route.to(Destination.RESOURCES)));
            assertFalse("accepts is the inner target's", routes.accepts(Route.to(Destination.RESOURCES).withPayload("x")));
            assertNull(routes.redirect(Route.to(Destination.RESOURCES)));
            resources.redirect = Route.to(Destination.RUNS);
            assertSame("…and so is redirect", resources.redirect, routes.redirect(Route.to(Destination.RESOURCES)));
            resources.redirect = null;

            RunsDpsPage.PageState fromFeed = (RunsDpsPage.PageState) routes.captureState();
            assertEquals(new RunsDpsPage.PageState(RunsTab.FEED, "feed-1"), fromFeed);
            assertEquals("Only the front tab's owner is captured", 0, meterOwner.captures);
            assertEquals("The wrapped target's own state is not the page's", 0, resources.captures);

            Route route = Route.to(Destination.RESOURCES);
            routes.open(route);
            assertEquals(RunsTab.LIVE_METER, page.selectedTab());
            assertEquals(List.of(route), resources.opened);
            assertEquals("The tab comes forward before the inner target opens", List.of(RunsTab.LIVE_METER), resources.frontAtOpen);
            assertEquals(new RunsDpsPage.PageState(RunsTab.LIVE_METER, "meter-1"), routes.captureState());

            page.bring(RunsTab.DUNGEONS);
            assertEquals("A tab without an owner captures no inner state", new RunsDpsPage.PageState(RunsTab.DUNGEONS, null), routes.captureState());

            routes.restoreState(fromFeed);
            assertEquals(RunsTab.FEED, page.selectedTab());
            assertEquals(List.of("feed-1"), feedOwner.restored);
            assertEquals("Back brings the tab forward first, then restores its owner", List.of(RunsTab.FEED), feedOwner.frontAtRestore);
            assertTrue(meterOwner.restored.isEmpty());

            assertTrue(page.tabs().hide("live-meter"));
            routes.restoreState(new RunsDpsPage.PageState(RunsTab.LIVE_METER, "meter-2"));
            assertEquals("Back is explicit navigation: it shows a tab hidden since", RunsTab.LIVE_METER, page.selectedTab());
            assertEquals(List.of("meter-2"), meterOwner.restored);
            routes.restoreState(new RunsDpsPage.PageState(RunsTab.RECORDINGS, null));
            assertEquals(RunsTab.RECORDINGS, page.selectedTab());
            assertEquals("Nothing to restore without an owner state", List.of("meter-2"), meterOwner.restored);
            try { routes.restoreState("foreign"); fail("A foreign state is rejected"); } catch (IllegalArgumentException expected) { }
            try { page.owner(RunsTab.FEED, routes); fail("A page target is never an owner: its capture would recurse"); }
            catch (IllegalArgumentException expected) { }
            return null;
        });
    }

    @Test public void aFailedOpenBringsBackThePreviousTabAndHidesTheTabAgain() throws Exception {
        PropertiesManager.setProperties(ORDER, "feed,dungeons,live-meter,recordings|live-meter");
        RunsDpsPage page = page();
        Target meter = new Target(Destination.ENCOUNTER, null);
        meter.reject = true;
        edt(() -> {
            RouteTarget routes = page.routes(RunsTab.LIVE_METER, meter);
            page.bring(RunsTab.DUNGEONS);
            try { routes.open(Route.to(Destination.ENCOUNTER)); fail("The rejection reaches the navigator"); }
            catch (IllegalArgumentException expected) { }
            assertEquals("A rejected route changes no tab", RunsTab.DUNGEONS, page.selectedTab());
            assertEquals(Collections.singleton("live-meter"), page.tabs().hiddenIds());
            assertEquals(Arrays.asList("Feed", "Dungeons", "Resources & buffs", "Recordings"), titles(page.tabs().component()));
            assertEquals("…nor the saved hidden set", "feed,dungeons,live-meter,resources,recordings|live-meter", PropertiesManager.getProperty(ORDER));

            page.bring(RunsTab.LIVE_METER);
            page.bring(RunsTab.RECORDINGS);
            try { routes.open(Route.to(Destination.ENCOUNTER)); fail(); } catch (IllegalArgumentException expected) { }
            assertEquals(RunsTab.RECORDINGS, page.selectedTab());
            assertTrue("A tab shown before stays shown", page.tabs().hiddenIds().isEmpty());
            return null;
        });
    }

    @Test public void theTabTargetTakesRunsFocusRoutesAndHandsTheFeedItsDungeon() throws Exception {
        assertEquals(new RunsFocus(RunsTab.DUNGEONS, null), RunsFocus.of(RunsTab.DUNGEONS));
        assertNull("A blank dungeon is none", new RunsFocus(RunsTab.FEED, " ").dungeon());
        try { new RunsFocus(null, null); fail(); } catch (NullPointerException expected) { }
        try { new RunsFocus(RunsTab.RECORDINGS, "Abyss of Demons"); fail("The dungeon is the Feed's filter only"); }
        catch (IllegalArgumentException expected) { }
        RunsDpsPage page = page();
        Target feedOwner = new Target(Destination.RUNS, "feed-1");
        edt(() -> {
            page.owner(RunsTab.FEED, feedOwner);
            RouteTarget target = page.tabTarget();
            Route recordings = Route.to(Destination.RUNS).withPayload(RunsFocus.of(RunsTab.RECORDINGS));
            Route abyss = Route.to(Destination.RUNS).withPayload(new RunsFocus(RunsTab.FEED, "Abyss of Demons"));
            assertEquals(Destination.RUNS, target.destination());
            assertTrue(target.accepts(recordings));
            assertFalse("A plain Runs route is the feed target's", target.accepts(Route.to(Destination.RUNS)));
            assertFalse(target.accepts(Route.to(Destination.RUNS).withPayload("recordings")));
            assertFalse(target.accepts(Route.to(Destination.ENCOUNTER).withPayload(RunsFocus.of(RunsTab.LIVE_METER))));
            assertFalse(target.accepts(recordings.withVisit(new VisitRef(HomeHistoryFixture.MORNING, "c1"))));
            assertFalse(target.accepts(recordings.withRecording("rec-1", null)));
            assertFalse(target.accepts(recordings.withBounds(1L, 2L)));
            assertFalse("A dungeon is rejected, not dropped, while nothing filters the feed", target.accepts(abyss));

            Object before = target.captureState();
            assertEquals(new RunsDpsPage.PageState(RunsTab.FEED, "feed-1"), before);
            target.open(recordings);
            assertEquals(RunsTab.RECORDINGS, page.selectedTab());
            try { target.open(abyss); fail("Unsupported routes are rejected"); } catch (IllegalArgumentException expected) { }
            assertEquals(RunsTab.RECORDINGS, page.selectedTab());

            List<String> filtered = new ArrayList<>();
            page.onFeedDungeon(filtered::add);
            assertTrue(target.accepts(abyss));
            JLabel recap = new JLabel("Recap");
            page.feed().setRecap(recap);
            page.feed().showRecap();
            target.open(abyss);
            assertEquals(RunsTab.FEED, page.selectedTab());
            assertEquals(List.of("Abyss of Demons"), filtered);
            assertFalse("The filtered feed shows, not a recap", page.feed().recapShown());
            target.open(recordings);
            assertEquals("Only a Feed route with a dungeon filters", List.of("Abyss of Demons"), filtered);

            page.onFeedDungeon(name -> { throw new IllegalStateException("filter failed"); });
            page.feed().showRecap();
            try { target.open(abyss); fail(); } catch (IllegalStateException expected) { }
            assertEquals("A failed open changes nothing", RunsTab.RECORDINGS, page.selectedTab());
            assertTrue(page.feed().recapShown());

            target.restoreState(before);
            assertEquals("The tab target restores the same composite state", RunsTab.FEED, page.selectedTab());
            assertEquals(List.of("feed-1"), feedOwner.restored);
            return null;
        });
    }

    @Test public void theLiveMeterTargetTakesPlainEncounterRoutesAndFocusesAfterThePageShows() throws Exception {
        RunsDpsPage page = page();
        Target meterOwner = new Target(Destination.ENCOUNTER, "meter-1");
        int[] focused = {0};
        RouteTarget target = edt(() -> {
            page.owner(RunsTab.LIVE_METER, meterOwner);
            return page.liveMeterTarget(() -> focused[0]++);
        });
        edt(() -> {
            Route plain = Route.to(Destination.ENCOUNTER);
            assertEquals(Destination.ENCOUNTER, target.destination());
            assertTrue(target.accepts(plain));
            assertFalse("An exact recording is the meter's own target", target.accepts(plain.withRecording("rec-1", null)));
            assertFalse(target.accepts(plain.withRecording(null, 7)));
            assertFalse(target.accepts(plain.withVisit(new VisitRef(HomeHistoryFixture.MORNING, "c1"))));
            assertFalse(target.accepts(plain.withPayload(RunsFocus.of(RunsTab.LIVE_METER))));
            assertFalse(target.accepts(plain.withBounds(1L, 2L)));
            assertFalse(target.accepts(Route.to(Destination.RESOURCES)));
            try { target.open(plain.withRecording("rec-1", null)); fail(); } catch (IllegalArgumentException expected) { }

            Object before = target.captureState();
            assertEquals(new RunsDpsPage.PageState(RunsTab.FEED, null), before);
            target.open(plain);
            assertEquals(RunsTab.LIVE_METER, page.selectedTab());
            assertEquals("The meter's selection is not changed", 0, meterOwner.opened.size() + meterOwner.restored.size());
            assertEquals("Focus waits until the navigator has shown the page", 0, focused[0]);
            return null;
        });
        edt(() -> {
            assertEquals(1, focused[0]);
            assertEquals(new RunsDpsPage.PageState(RunsTab.LIVE_METER, "meter-1"), target.captureState());
            target.restoreState(new RunsDpsPage.PageState(RunsTab.FEED, null));
            assertEquals(RunsTab.FEED, page.selectedTab());
            target.open(Route.to(Destination.ENCOUNTER));
            page.bring(RunsTab.DUNGEONS);   // another tab came forward before the deferred focus ran
            return null;
        });
        edt(() -> { assertEquals("A tab no longer in front is not focused", 1, focused[0]); return null; });
    }

    @Test public void closeReachesEveryTabContentIncludingHiddenTabsOnce() throws Exception {
        PropertiesManager.setProperties(ORDER, "feed,dungeons,live-meter,recordings|feed,live-meter,recordings");
        Closing meter = new Closing(), recordings = new Closing(), dungeons = new Closing(), resources = new Closing(), replaced = new Closing();
        RunsDpsPage page = page(meter);
        int[] feedClosed = {0};
        edt(() -> {
            page.feed().onClose(() -> feedClosed[0]++);
            page.setContent(RunsTab.RECORDINGS, replaced);
            page.setContent(RunsTab.RECORDINGS, recordings);
            page.setContent(RunsTab.DUNGEONS, dungeons);
            page.setContent(RunsTab.RESOURCES, resources);
            assertTrue(page.tabs().hide("resources"));
            assertEquals(Collections.singletonList("dungeons"), page.tabs().visibleIds());
            dungeons.failure = new IllegalStateException("dungeons failed");
            try { page.close(); fail("A failed close is reported"); }
            catch (IllegalStateException expected) { assertEquals("dungeons failed", expected.getMessage()); }
            assertEquals("A hidden Feed is closed", 1, feedClosed[0]);
            assertEquals("A hidden Live meter is closed", 1, meter.closed);
            assertEquals("A hidden holder's content is closed", 1, recordings.closed);
            assertEquals("Hidden Resources closes too", 1, resources.closed);
            assertEquals(1, dungeons.closed);
            assertEquals("Replaced content belongs to the caller", 0, replaced.closed);
            page.close();
            assertEquals("Closing is idempotent", 1, feedClosed[0]);
            assertEquals(1, resources.closed);
            assertEquals(1, meter.closed);
            assertEquals(1, recordings.closed);
            assertEquals(1, dungeons.closed);
            return null;
        });
    }
}
