package tomato.gui.stats;

import java.util.*;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewState;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.stats.LootQuery.*;
import tomato.history.archive.ArchiveQuery;

/**
 * Loot › Explore's one view (spec §6.4, P6a): the {@link View} the live dashboard and the saved archive show, shared by their two
 * selectors until P6b merges the scope row into one filter bar. Simple lists the nine item views, Analyst adds the six saved-only
 * views; Dungeon statistics stays in Dungeons › Analysis and Character fame moves to Characters.
 *
 * <p>A view the user chooses carries across: one chosen live becomes the saved query's view at once (so Browse saved opens on
 * it, without reading while live), and one chosen in saved history becomes the live view when the live dashboard has it, All
 * Items otherwise. A saved-only view chosen while live opens saved history with that view (the {@code ArchiveRouteTarget.open}
 * pattern), captioned {@value #SAVED_ONLY}. Restores, drills and routes change only their own side, so an old live index and
 * an old saved {@code facets.view} both restore as they were. The Simple/Analyst mode never changes a query: a current view the
 * mode does not list stays in the selector as its "Current view". EDT only.
 */
final class LootExploreModel {
    /** The Simple views, in the spec's order. */
    static final List<View> SIMPLE = List.of(View.ITEMS, View.POTIONS, View.WHITES, View.BAGS, View.DUNGEONS, View.UTS, View.STS, View.TIERED, View.RECENT);
    /** The Analyst views: saved history only. */
    static final List<View> ANALYST = List.of(View.OCCURRENCES, View.RATES, View.SESSIONS, View.COHORTS, View.ENEMIES, View.SOURCES);
    /**
     * The live dashboard's views by their persisted index: the live view state writes this index under {@code loot-views} and the
     * tables are named {@code loot-view-<index>}, so the order never changes.
     */
    static final List<View> LIVE = List.of(View.ITEMS, View.POTIONS, View.WHITES, View.BAGS, View.RECENT, View.DUNGEONS, View.UTS, View.STS, View.TIERED);
    static final String SAVED_ONLY = "Saved history only";
    private static final Map<View, String> TIPS = Map.of(
        View.UTS, "UT weapons, abilities, armor and rings; excludes potions, runes and other consumables",
        View.STS, "Only items labeled ST, regardless of bag color",
        View.TIERED, "Tiered weapons and armor T13+; abilities T6+");

    private final DisplayModeModel mode;
    private LootDashboard live;
    private ArchiveWorkspace<Row, Facets, Sort> saved;

    LootExploreModel(DisplayModeModel mode) { this.mode = Objects.requireNonNull(mode, "mode"); }

    /** The views Loot's saved archive offers: the Simple and the Analyst ones. */
    static Set<View> views() { Set<View> views = EnumSet.copyOf(SIMPLE); views.addAll(ANALYST); return Collections.unmodifiableSet(views); }
    /**
     * Saved Loot's first query: All Items of the current session, newest first. It is the first Simple view (and the live
     * dashboard's first), so a fresh Browse saved never opens on an Analyst view; routes and drills still ask for Item occurrences.
     */
    static ArchiveQuery<Facets, Sort> initialQuery() { return LootQuery.initial(View.ITEMS, ArchiveQuery.CURRENT); }
    static boolean live(View view) { return LIVE.contains(view); }
    /** The view at a persisted live index; All Items for an index out of range. */
    static View liveView(int index) { return index >= 0 && index < LIVE.size() ? LIVE.get(index) : View.ITEMS; }
    static String title(View view) { return view.toString(); }
    static String tooltip(View view) { return live(view) ? TIPS.get(view) : SAVED_ONLY + ": opens saved loot in this view"; }

    DisplayModeModel mode() { return mode; }

    /** Pairs the Loot workspace's live dashboard and the workspace itself; the dashboard then offers the saved-only views too. */
    void attach(LootDashboard live, ArchiveWorkspace<Row, Facets, Sort> saved) {
        this.live = Objects.requireNonNull(live, "live");
        this.saved = Objects.requireNonNull(saved, "saved");
        live.explore(this);
    }

    /**
     * The live selector's user choice. A live view becomes the saved query's view while the workspace stays live (nothing is
     * read); a saved-only view opens saved history with it. Returns whether the live dashboard shows {@code view}.
     */
    boolean chosenLive(View view) {
        if (saved == null) return live(view);
        ViewState<Facets, Sort> state = saved.state();
        Facets facets = state.query.facets();
        if (live(view) && facets.view == view) return true;
        facets.view = view;
        ViewState<Facets, Sort> next = state.withQuery(state.query.withFacets(facets)).withPosition(view.name(), Collections.emptyList(), null, 0);
        saved.restore(live(view) ? next : next.withArchive(true));
        return live(view);
    }

    /** The saved selector's user choice (its query already changed): the live dashboard shows it when it has it, All Items otherwise. */
    void chosenSaved(View view) { if (live != null) live.showView(live(view) ? view : View.ITEMS); }
}
