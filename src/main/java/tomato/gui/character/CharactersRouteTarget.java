package tomato.gui.character;

import java.util.List;
import java.util.Objects;
import javax.swing.SwingUtilities;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * Routes into the Characters Roster tab:
 * - {@link Destination#CHARACTERS} without a payload shows the character list.
 * - {@link Destination#CHARACTER_SHEET} with a {@link SheetFocus} shows that character's sheet; its tab, when named, is shown and selected.
 * Both targets share one view and capture {@link CharactersState}, so Back from a sheet returns to the list with its filters,
 * selection and scroll. Opening a route and Back both bring the Roster tab forward. EDT only.
 */
public final class CharactersRouteTarget implements RouteTarget {
    /** Detached Back state: whether the sheet was showing, and its character and tab. */
    public record CharactersState(boolean sheet, String key, String tab) { }

    /** The Back entry the next open pushes, and the Characters view it returns to, as last captured. */
    private static final class Origin { long token; CharactersState state; }

    private final Destination destination;
    private final CharacterRosterView view;
    private final Origin origin;

    private CharactersRouteTarget(Destination destination, CharacterRosterView view, Origin origin) {
        this.destination = destination; this.view = Objects.requireNonNull(view, "view"); this.origin = origin;
    }

    /** The list target and the sheet target over one Roster view; a RouteTarget has one destination, so register both. */
    public static List<RouteTarget> of(CharacterRosterView view) {
        Origin origin = new Origin();
        return List.of(new CharactersRouteTarget(Destination.CHARACTERS, view, origin), new CharactersRouteTarget(Destination.CHARACTER_SHEET, view, origin));
    }

    @Override public Destination destination() { return destination; }
    @Override public boolean accepts(Route route) {
        if (route.destination != destination || route.query != null || route.visit != null || route.record != null
            || route.recordingId != null || route.localObjectId != null || route.from != null || route.until != null) return false;
        return destination == Destination.CHARACTERS ? route.payload == null : route.payload instanceof SheetFocus;
    }
    @Override public Object captureState() {
        CharactersState state = view.state();
        origin.state = state; origin.token = view.navigator().nextBackToken();
        return state;
    }
    @Override public void open(Route route) {
        if (!accepts(route)) throw new IllegalArgumentException("Unsupported Characters route: " + route);
        long entry = view.navigator().nextBackToken();
        // Spec §6.2: the link leads back to the list. It pops this open's Back entry only when that entry returns to the list.
        boolean fromList = entry != 0 && origin.token == entry && origin.state != null && !origin.state.sheet();
        origin.token = 0; origin.state = null;
        view.reveal();
        if (destination == Destination.CHARACTERS) { view.showList(); return; }
        SheetFocus focus = (SheetFocus) route.payload;
        view.showSheet(focus.key(), focus.tab(), () -> {
            Navigator navigator = view.navigator();
            if (fromList && navigator.backToken() == entry) navigator.back(); else view.showList();
        });
        // Explicit navigation into the sheet (Alt+7's Build redirect, the Goals search entry, a row/card open) moves keyboard
        // focus in: one place instead of each caller doing it. Restoring a saved tab (restoreState) never does this. Deferred:
        // ShellNavigator.open calls target.open (here) BEFORE it actually selects/shows the destination page, so from another
        // page the sheet is still hidden and requestFocusInWindow() would fail; invokeLater runs after the page is shown.
        SwingUtilities.invokeLater(view.sheet()::focusBackLink);
    }
    @Override public void restoreState(Object state) {
        if (!(state instanceof CharactersState)) throw new IllegalArgumentException("Not a Characters view state");
        CharactersState saved = (CharactersState) state;
        // Back is explicit navigation, as routes are: after a plain click on another Characters tab (Exalts, Pets), the restored
        // list or sheet must be the one in front, or Back looks like it did nothing.
        view.reveal();
        if (saved.sheet() && saved.key() != null) view.showSheet(saved.key(), saved.tab(), view::showList);
        else view.showList();
    }
}
