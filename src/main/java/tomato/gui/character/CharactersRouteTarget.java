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
 * selection and scroll. Opening a route brings the Roster tab forward; Back brings forward the Characters tab that was in front
 * when its state was captured (Roster, Exalts or Pets), so Back returns to the tab the user left from. EDT only.
 */
public final class CharactersRouteTarget implements RouteTarget {
    /**
     * Detached Back state: whether the sheet was showing, its character and tab, and the id of the Characters page tab in front
     * when the state was captured ({@code pageTab}: "roster", "exalts", "pets"; null when unknown).
     */
    public record CharactersState(boolean sheet, String key, String tab, String pageTab) {
        /** A state without the page tab (older callers) means the Roster tab was in front. */
        public CharactersState(boolean sheet, String key, String tab) { this(sheet, key, tab, "roster"); }
        /** Whether the Roster tab was in front; an unknown (null) page tab counts as Roster. */
        public boolean roster() { return pageTab == null || "roster".equals(pageTab); }
    }

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
        // Spec §6.2: the link leads back to the list. It pops this open's Back entry only when that entry returns to the list on the
        // Roster tab; an entry captured on Exalts or Pets would bring that tab forward, so the link shows the list in place instead.
        boolean fromList = entry != 0 && origin.token == entry && origin.state != null && !origin.state.sheet() && origin.state.roster();
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
        // Back returns where you were. Captured with the Roster tab in front (e.g. the list a sheet was opened from), the restored
        // list or sheet must be in front again, even after a plain click on Exalts or Pets, or Back looks like it did nothing.
        // Captured with another Characters tab in front (a route left the page from Exalts, or opened a sheet from Pets and so
        // brought Roster forward), that tab comes forward again and the roster is restored behind it. The tab comes first, so
        // restoring the list never moves keyboard focus into a Roster card that is about to be hidden.
        if (saved.roster()) view.reveal(); else view.bringTabForward(saved.pageTab());
        if (saved.sheet() && saved.key() != null) view.showSheet(saved.key(), saved.tab(), view::showList);
        else view.showList();
    }
}
