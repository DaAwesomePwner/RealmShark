package tomato.gui.myinfo;

import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.Test;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.route.ShellNavigator;
import static org.junit.Assert.*;

/**
 * P6a: Build has no page of its own. A Build route always leaves for the Characters page: the character's sheet on its Build tab,
 * or, with no character at all, the Characters list, whose gallery says "No characters yet".
 */
public class BuildRouteTest {
    private static final String KEY = "0".repeat(64) + ":7";   // a journal character key

    @Test public void withoutACharacterTheBuildRouteRedirectsToCharacters() {
        Route redirected = new BuildRoute(() -> null).redirect(Route.to(Destination.MY_INFO));
        assertNotNull("No character: Characters, never a Build page", redirected);
        assertEquals(Destination.CHARACTERS, redirected.destination);
        assertNull("A plain Characters route (the list), not a sheet", redirected.payload);
        assertNull(redirected.visit); assertNull(redirected.query); assertNull(redirected.recordingId);
    }

    @Test public void withACharacterTheBuildRouteRedirectsToThatSheetsBuildTab() {
        Route redirected = new BuildRoute(() -> KEY).redirect(Route.to(Destination.MY_INFO));
        assertEquals(Destination.CHARACTER_SHEET, redirected.destination);
        assertEquals(new SheetFocus(KEY, "build"), redirected.payload);
    }

    @Test public void theBuildDestinationMapsToTheCharactersPage() {
        assertEquals("Every destination maps to a real page", "characters", WorkspaceShell.pageOf(Destination.MY_INFO));
    }

    /** Through a navigator: no character opens Characters with a Back entry to where the route was used. */
    @Test public void aBuildRouteWithoutACharacterOpensCharactersAndBackReturns() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String[] page = {"home"};
            List<Route> opened = new ArrayList<>();
            ShellNavigator navigator = new ShellNavigator(() -> page[0], value -> page[0] = value, WorkspaceShell::pageOf, 5);
            navigator.register(new RouteTarget() {   // the Characters list accepts plain routes, as CharactersRouteTarget does
                @Override public Destination destination() { return Destination.CHARACTERS; }
                @Override public boolean accepts(Route route) { return route.destination == Destination.CHARACTERS && route.payload == null; }
                @Override public Object captureState() { return null; }
                @Override public void open(Route route) { opened.add(route); }
                @Override public void restoreState(Object state) { }
            });
            navigator.register(new BuildRoute(() -> null));
            assertTrue(navigator.open(Route.to(Destination.MY_INFO)));
            assertEquals("characters", page[0]);
            assertEquals("The Characters target opened the list", 1, opened.size());
            assertEquals(Destination.CHARACTERS, opened.get(0).destination);
            assertTrue(navigator.back());
            assertEquals("home", page[0]);
        });
    }
}
