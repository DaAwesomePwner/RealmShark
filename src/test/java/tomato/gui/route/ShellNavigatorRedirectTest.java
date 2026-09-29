package tomato.gui.route;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** One-hop redirects (Build now opens on the character sheet): one Back entry, and the original route when nobody accepts. */
public class ShellNavigatorRedirectTest {
    @Test public void aTargetMayRedirectOnceToARouteAnotherTargetAccepts() throws Exception {
        ShellNavigatorTest.edt(() -> {
            ShellNavigatorTest.Pages pages = new ShellNavigatorTest.Pages();
            ShellNavigator navigator = pages.navigator(5);
            Route[] next = {Route.to(Destination.TIMELINE)};
            ShellNavigatorTest.Fake runs = new ShellNavigatorTest.Fake(Destination.RUNS, pages.log) {
                @Override public Route redirect(Route route) { return next[0]; }
            };
            ShellNavigatorTest.Fake timeline = new ShellNavigatorTest.Fake(Destination.TIMELINE, pages.log) {
                @Override public Route redirect(Route route) { return Route.to(Destination.RUNS); } // never followed: one hop only
            };
            navigator.register(runs); navigator.register(timeline);
            pages.selected = "loot";
            assertTrue(navigator.open(Route.to(Destination.RUNS)));
            assertEquals(Arrays.asList("open TIMELINE", "select timeline"), pages.log);
            assertNull("The redirecting target does not open", runs.opened);
            assertEquals("One Back entry, to the true origin", 1, navigator.depth());
            assertTrue(navigator.back());
            assertEquals("loot", pages.selected);
            pages.log.clear();
            next[0] = Route.to(Destination.LOOT);
            assertTrue("A redirect nobody accepts keeps the original route", navigator.open(Route.to(Destination.RUNS)));
            assertEquals(Arrays.asList("open RUNS", "select runs"), pages.log);
            return null;
        });
    }
}
