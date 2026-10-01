package tomato.gui.route;

import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;
import static tomato.gui.route.ShellNavigatorTest.edt;
import tomato.gui.route.ShellNavigatorTest.Fake;
import tomato.gui.route.ShellNavigatorTest.Pages;

public class ShellNavigatorForwardTest {
    @Test public void roundTripRestoresDestinationAndOriginalBackToken() throws Exception {
        edt(() -> {
            Pages pages = new Pages(); ShellNavigator nav = pages.navigator(3);
            Fake runs = new Fake(Destination.RUNS, pages.log), loot = new Fake(Destination.LOOT, pages.log);
            nav.register(runs); nav.register(loot);
            pages.selected = "runs"; runs.state = "origin";
            assertTrue(nav.open(Route.to(Destination.LOOT)));
            long originalToken = nav.backToken();
            loot.state = "selected item";
            assertTrue(nav.back());
            assertEquals("origin", runs.state); assertEquals("loot", nav.forwardPage());
            loot.state = "changed while away";
            assertTrue(nav.forward());
            assertEquals("loot", pages.selected); assertEquals("selected item", loot.state);
            assertEquals(originalToken, nav.backToken());
            assertFalse(nav.canGoForward()); assertFalse(nav.forward()); assertNull(nav.forwardPage());
            assertTrue(nav.back()); assertEquals("runs", pages.selected); assertEquals("origin", runs.state);
            return null;
        });
    }

    @Test public void historyCapturesCannotMatchTheNextOpenToken() throws Exception {
        edt(() -> {
            Pages pages = new Pages(); ShellNavigator nav = pages.navigator(3);
            long[] captured = {0};
            for (Destination destination : new Destination[] {Destination.RUNS, Destination.LOOT}) {
                nav.register(new Fake(destination, pages.log) {
                    @Override public Object captureState() {
                        captured[0] = nav.nextBackToken();
                        return super.captureState();
                    }
                });
            }
            pages.selected = "runs";
            assertTrue(nav.open(Route.to(Destination.LOOT)));
            long expected = nav.nextBackToken();
            assertTrue(nav.back());
            assertEquals(expected, captured[0]); assertEquals(expected + 1, nav.nextBackToken());
            expected = nav.nextBackToken();
            assertTrue(nav.forward());
            assertEquals(expected, captured[0]); assertEquals(expected + 1, nav.nextBackToken());
            return null;
        });
    }

    @Test public void inPageBackLinkStillPopsItsEntryAfterBackAndForward() throws Exception {
        edt(() -> {
            Pages pages = new Pages(); ShellNavigator nav = pages.navigator(3);
            long[] entry = {0};
            nav.register(new Fake(Destination.RUNS, pages.log) {
                @Override public void open(Route route) { entry[0] = nav.nextBackToken(); super.open(route); }
            });
            assertTrue(nav.open(Route.to(Destination.RUNS)));
            assertTrue(nav.back()); assertTrue(nav.forward());
            assertEquals(entry[0], nav.backToken());
            assertTrue(nav.backToken() == entry[0] && nav.back());
            assertEquals("chat", pages.selected); assertEquals(0, nav.depth());
            return null;
        });
    }

    @Test public void severalStepsPreserveOrderAndBoundBothStacks() throws Exception {
        edt(() -> {
            Pages pages = new Pages(); ShellNavigator nav = pages.navigator(3);
            Fake runs = new Fake(Destination.RUNS, pages.log); nav.register(runs); pages.selected = "runs";
            for (int i = 0; i < 5; i++) { runs.state = i; assertTrue(nav.open(Route.to(Destination.RUNS))); }
            runs.state = 5;
            for (int i = 4; i >= 2; i--) { assertTrue(nav.back()); assertEquals(i, runs.state); assertTrue(nav.forwardDepth() <= 3); }
            assertFalse(nav.back()); assertEquals(3, nav.forwardDepth());
            for (int i = 3; i <= 5; i++) {
                assertTrue(nav.forward()); assertEquals(i, runs.state); assertTrue(nav.depth() <= 3);
                assertEquals("Each restored Back entry keeps its original identity", i, nav.backToken());
            }
            assertFalse(nav.forward()); assertEquals(3, nav.depth());
            return null;
        });
    }

    @Test public void onlySuccessfulOpenClearsForwardAndNotifies() throws Exception {
        edt(() -> {
            Pages pages = new Pages(); ShellNavigator nav = pages.navigator(3);
            Fake runs = new Fake(Destination.RUNS, pages.log); nav.register(runs);
            assertTrue(nav.open(Route.to(Destination.RUNS))); assertTrue(nav.back());
            int[] changes = {0}; nav.addChangeListener(() -> changes[0]++);
            assertFalse(nav.open(null)); assertFalse(nav.open(Route.to(Destination.LOOT)));
            runs.fail = true; assertFalse(nav.open(Route.to(Destination.RUNS)));
            assertEquals(1, nav.forwardDepth()); assertEquals(0, changes[0]);
            runs.fail = false; assertTrue(nav.open(Route.to(Destination.RUNS)));
            assertFalse(nav.canGoForward()); assertEquals(1, changes[0]);
            return null;
        });
    }

    @Test public void brokenSnapshotStillAllowsBackAndPageOnlyForward() throws Exception {
        edt(() -> {
            Pages pages = new Pages(); ShellNavigator nav = pages.navigator(3);
            Fake runs = new Fake(Destination.RUNS, pages.log) {
                @Override public Object captureState() { throw new IllegalStateException("broken snapshot"); }
                @Override public void restoreState(Object state) { fail("No snapshot should be restored"); }
            };
            nav.register(runs);
            assertTrue(nav.open(Route.to(Destination.RUNS))); assertTrue(nav.back());
            assertEquals("chat", pages.selected); assertTrue(nav.forward()); assertEquals("runs", pages.selected);
            assertTrue(nav.canGoBack());
            return null;
        });
    }

    @Test public void unregisteredDestinationIsNotRestoredAndNoneHasNoForward() throws Exception {
        assertFalse(Navigator.NONE.forward()); assertFalse(Navigator.NONE.canGoForward());
        edt(() -> {
            Pages pages = new Pages(); ShellNavigator nav = pages.navigator(3);
            Fake runs = new Fake(Destination.RUNS, new ArrayList<>());
            nav.register(runs); nav.open(Route.to(Destination.RUNS)); nav.back();
            nav.unregister(runs); runs.state = "untouched";
            assertTrue(nav.forward()); assertEquals("runs", pages.selected); assertEquals("untouched", runs.state);
            return null;
        });
    }

    @Test public void forwardMethodsRequireEdt() {
        ShellNavigator nav = new Pages().navigator(1);
        for (Runnable call : new Runnable[] {nav::forward, nav::canGoForward, nav::forwardPage, nav::forwardDepth}) {
            try { call.run(); fail("Off-EDT call must fail"); } catch (IllegalStateException expected) {}
        }
    }
}
