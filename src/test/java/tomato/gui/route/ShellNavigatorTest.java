package tomato.gui.route;

import org.junit.Test;
import tomato.history.link.VisitRef;

import javax.swing.SwingUtilities;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Registry, rejection, origin capture and Back-stack bounds, without Swing components. */
public class ShellNavigatorTest {
    private static final VisitRef VISIT = new VisitRef("11111111-2222-3333-4444-555555555555", "journal:1");

    @Test public void unknownOrUnacceptedRoutesAreRejectedWithoutAnyStateChange() throws Exception {
        edt(() -> {
            Pages pages = new Pages();
            ShellNavigator navigator = pages.navigator(5);
            Fake runs = new Fake(Destination.RUNS, pages.log); runs.state = "runs-origin";
            navigator.register(runs);
            pages.selected = 10;
            assertFalse("No target for this destination", navigator.open(Route.to(Destination.LOOT)));
            assertFalse(navigator.canOpen(Route.to(Destination.LOOT)));
            runs.accept = false;
            assertFalse("A target that cannot honour the references rejects the route", navigator.open(Route.to(Destination.RUNS).withVisit(VISIT)));
            Fake throwing = new Fake(Destination.TIMELINE, pages.log) { @Override public boolean accepts(Route route) { throw new IllegalStateException("broken"); } };
            navigator.register(throwing);
            assertFalse(navigator.open(Route.to(Destination.TIMELINE)));
            assertFalse(navigator.open(null));
            assertFalse(navigator.canGoBack()); assertEquals(0, navigator.depth());
            assertEquals(10, pages.selected);
            assertTrue("No capture, open or page change happened: " + pages.log, pages.log.isEmpty());
            return null;
        });
    }

    @Test public void originIsCapturedBeforeNavigationAndBackRestoresIt() throws Exception {
        edt(() -> {
            Pages pages = new Pages();
            ShellNavigator navigator = pages.navigator(5);
            Fake runs = new Fake(Destination.RUNS, pages.log), timeline = new Fake(Destination.TIMELINE, pages.log);
            navigator.register(runs); navigator.register(timeline);
            pages.selected = 10; runs.state = "session A · page 3 · row 7";
            assertTrue(navigator.canOpen(Route.to(Destination.TIMELINE).withVisit(VISIT)));
            assertTrue(navigator.open(Route.to(Destination.TIMELINE).withVisit(VISIT)));
            assertEquals(Arrays.asList("capture RUNS", "open TIMELINE", "select 11"), pages.log);
            assertEquals(VISIT, timeline.opened.visit);
            assertEquals(10, navigator.backPage());
            runs.state = "changed while away";
            pages.log.clear();
            assertTrue(navigator.back());
            assertEquals(Arrays.asList("restore RUNS session A · page 3 · row 7", "select 10"), pages.log);
            assertEquals("session A · page 3 · row 7", runs.state);
            assertFalse(navigator.canGoBack()); assertFalse(navigator.back());
            return null;
        });
    }

    @Test public void pagesWithoutTargetsStillReturnAndFailedOpensRollBack() throws Exception {
        edt(() -> {
            Pages pages = new Pages();
            ShellNavigator navigator = pages.navigator(5);
            Fake loot = new Fake(Destination.LOOT, pages.log);
            navigator.register(loot);
            pages.selected = 0; // Chat: no route target, only its page is remembered.
            assertTrue(navigator.open(Route.to(Destination.LOOT)));
            assertEquals(8, pages.selected);
            loot.fail = true; pages.log.clear();
            assertFalse(navigator.open(Route.to(Destination.LOOT)));
            assertEquals("A failed open leaves the current page and stack alone", 8, pages.selected);
            assertEquals(1, navigator.depth());
            assertTrue(navigator.back()); assertEquals(0, pages.selected);
            // Dialog destinations have no page: they open beside the current view and add no Back entry.
            Fake draft = new Fake(Destination.ALERT_DRAFT, pages.log);
            navigator.register(draft);
            assertTrue(navigator.open(Route.to(Destination.ALERT_DRAFT).withPayload("sample")));
            assertEquals(0, pages.selected); assertFalse(navigator.canGoBack());
            return null;
        });
    }

    @Test public void backStackIsBoundedAndDropsTheOldestOrigins() throws Exception {
        edt(() -> {
            Pages pages = new Pages();
            ShellNavigator navigator = pages.navigator(3);
            Fake runs = new Fake(Destination.RUNS, pages.log);
            navigator.register(runs);
            pages.selected = 10;
            for (int i = 0; i < 5; i++) { runs.state = "origin " + i; assertTrue(navigator.open(Route.to(Destination.RUNS))); }
            assertEquals(3, navigator.depth());
            List<Object> restored = new ArrayList<>();
            while (navigator.back()) restored.add(runs.state);
            assertEquals(Arrays.asList("origin 4", "origin 3", "origin 2"), restored);
            try { new ShellNavigator(() -> 0, page -> {}, d -> 0, 0); fail(); } catch (IllegalArgumentException expected) {}
            return null;
        });
    }

    @Test public void newestRegistrationWinsAndUnregisteredTargetsAreNotRestored() throws Exception {
        edt(() -> {
            Pages pages = new Pages();
            ShellNavigator navigator = pages.navigator(5);
            Fake generic = new Fake(Destination.RUNS, pages.log), specific = new Fake(Destination.RUNS, pages.log);
            generic.state = "generic"; specific.state = "specific";
            navigator.register(generic); navigator.register(specific);
            pages.selected = 10;
            assertTrue(navigator.open(Route.to(Destination.RUNS)));
            assertSame(specific.opened.destination, Destination.RUNS); assertNull(generic.opened);
            specific.accept = false;
            assertTrue("An earlier target still serves routes the newer one declines", navigator.open(Route.to(Destination.RUNS)));
            assertNotNull(generic.opened);
            assertTrue(navigator.unregister(specific));
            pages.log.clear();
            assertTrue(navigator.back()); assertTrue(navigator.back());
            assertFalse("Unregistered targets never receive a stale restore", pages.log.toString().contains("specific"));
            return null;
        });
    }

    @Test public void registryUsesTheInstalledShellNavigatorAndRequiresTheEdt() throws Exception {
        try { new Pages().navigator(1).canGoBack(); fail("Off-EDT use must fail"); } catch (IllegalStateException expected) {}
        edt(() -> {
            Navigator previous = Navigator.current();
            try {
                Navigator.install(null);
                assertFalse(NavigatorRegistry.register(new Fake(Destination.RUNS, new ArrayList<>())));
                assertFalse(Navigator.current().open(Route.to(Destination.RUNS)));
                Pages pages = new Pages(); ShellNavigator navigator = pages.navigator(2);
                Navigator.install(navigator);
                Fake runs = new Fake(Destination.RUNS, pages.log);
                assertTrue(NavigatorRegistry.register(runs));
                assertTrue(Navigator.current().canOpen(Route.to(Destination.RUNS)));
                assertTrue(NavigatorRegistry.unregister(runs));
                assertFalse(Navigator.current().canOpen(Route.to(Destination.RUNS)));
            } finally { Navigator.install(previous); }
            return null;
        });
    }

    static final class Pages {
        int selected;
        final List<String> log = new ArrayList<>();
        ShellNavigator navigator(int capacity) {
            return new ShellNavigator(() -> selected, page -> { selected = page; log.add("select " + page); }, Pages::page, capacity);
        }
        static int page(Destination destination) {
            switch (destination) {
                case RUNS: return 10;
                case TIMELINE: return 11;
                case LOOT: return 8;
                default: return ShellNavigator.NO_PAGE;
            }
        }
    }

    static class Fake implements RouteTarget {
        final Destination destination;
        final List<String> log;
        boolean accept = true, fail;
        Object state;
        Route opened;
        Fake(Destination destination, List<String> log) { this.destination = destination; this.log = log; }
        @Override public Destination destination() { return destination; }
        @Override public boolean accepts(Route route) { return accept && route.destination == destination; }
        @Override public Object captureState() { log.add("capture " + destination); return state; }
        @Override public void open(Route route) {
            if (fail) throw new IllegalStateException("synthetic failure");
            opened = route; log.add("open " + destination);
        }
        @Override public void restoreState(Object value) { state = value; log.add("restore " + destination + " " + value); }
    }

    interface Checked<T> { T get() throws Exception; }
    static <T> T edt(Checked<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(body.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
