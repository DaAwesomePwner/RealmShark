package tomato.gui.stats;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.*;
import java.util.List;
import javax.swing.*;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewState;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.ViewSelector;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootQuery.*;
import tomato.history.archive.ArchiveQuery;

/**
 * Loot › Explore's one view (spec §6.4) and its one selector (P6b): the live dashboard's {@code loot-views}, which this model
 * adopts and controls. Simple lists the nine item views, Analyst adds the six saved-only views; Dungeon statistics stays in
 * Dungeons › Analysis and Character fame moves to Characters.
 *
 * <p>The selector and the "Saved history only" caption ({@value #SAVED_ONLY}, shown for a saved-only view in saved history) form a
 * lead panel that the workspace places first in the filter row it shows ({@link ArchiveWorkspace#lead}): the live dashboard's row
 * while live, its own while saved. A view the user chooses carries across: one chosen live becomes the saved query's view at once
 * (so saved history opens on it, without reading while live), and one chosen in saved history becomes the live view when the live
 * dashboard has it, All Items otherwise. A saved-only view chosen while live opens saved history with that view (the
 * {@code ArchiveRouteTarget.open} pattern). Restores, drills and routes change only their own side, so an old live index and an
 * old saved {@code facets.view} both restore as they were; the selector shows the side in front. The Simple/Analyst mode never
 * changes a query: a current view the mode does not list stays in the selector as its "Current view". EDT only.
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
    /** The workspace ⋯ section holding the saved view's drill-downs (after the column tools). */
    static final String DRILLS = "loot-drills";
    private static final Map<View, String> TIPS = Map.of(
        View.UTS, "UT weapons, abilities, armor and rings; excludes potions, runes and other consumables",
        View.STS, "Only items labeled ST, regardless of bag color",
        View.TIERED, "Tiered weapons and armor T13+; abilities T6+");

    private final DisplayModeModel mode;
    private LootDashboard live;
    private ArchiveWorkspace<Row, Facets, Sort> saved;
    private final JPanel lead = new JPanel(new BorderLayout(6, 0));
    private final JLabel caption = new JLabel(SAVED_ONLY) {
        @Override public void updateUI() { super.updateUI(); setForeground(ContentStyle.color("muted")); }
    };

    LootExploreModel(DisplayModeModel mode) { this.mode = Objects.requireNonNull(mode, "mode"); }

    /** The views Loot's saved archive offers: the Simple and the Analyst ones. */
    static Set<View> views() { Set<View> views = EnumSet.copyOf(SIMPLE); views.addAll(ANALYST); return Collections.unmodifiableSet(views); }
    /**
     * Saved Loot's first query: All Items of the current session, newest first. It is the first Simple view (and the live
     * dashboard's first), so a fresh saved Loot never opens on an Analyst view; routes and drills still ask for Item occurrences.
     */
    static ArchiveQuery<Facets, Sort> initialQuery() { return LootQuery.initial(View.ITEMS, ArchiveQuery.CURRENT); }
    static boolean live(View view) { return LIVE.contains(view); }
    /** The view at a persisted live index; All Items for an index out of range. */
    static View liveView(int index) { return index >= 0 && index < LIVE.size() ? LIVE.get(index) : View.ITEMS; }
    static String title(View view) { return view.toString(); }
    static String tooltip(View view) { return live(view) ? TIPS.get(view) : SAVED_ONLY + ": opens saved loot in this view"; }

    DisplayModeModel mode() { return mode; }

    /**
     * Pairs the Loot workspace's live dashboard and the workspace itself: adopts the dashboard's selector into the lead panel with
     * the caption, has the workspace place it, and follows the workspace's live/saved switches and the display mode.
     */
    void attach(LootDashboard live, ArchiveWorkspace<Row, Facets, Sort> saved) {
        this.live = Objects.requireNonNull(live, "live");
        this.saved = Objects.requireNonNull(saved, "saved");
        live.explore(this);
        caption.setName("loot-archive-view-caption");
        caption.setFont(ContentStyle.metadata(ContentStyle.body()));
        caption.putClientProperty("html.disable", true);
        caption.setToolTipText("This view reads saved history; the live view has no equivalent.");
        lead.setName("loot-views-lead");
        lead.setOpaque(false);
        lead.add(live.selector().component(), BorderLayout.WEST);
        lead.add(caption, BorderLayout.CENTER);
        saved.addPropertyChangeListener(ArchiveWorkspace.ARCHIVE, event -> modeChanged(Boolean.TRUE.equals(event.getNewValue())));
        // The dashboard stays in the workspace's cards, so it owns the binding (the lead panel moves between rows).
        mode.bind(live, value -> modeChanged(saved.state().archive));
        saved.lead(lead);
    }

    /**
     * The selector's user choice (its only listener). Live, a live view is shown and becomes the saved query's view without a read;
     * a saved-only view opens saved history with it. Saved, the saved query takes the view (its own position, page 0), and the live
     * dashboard shows it when it has it, All Items otherwise.
     */
    void choose(View view) {
        if (saved == null) { live.showLive(view); return; }
        ViewState<Facets, Sort> state = saved.state();
        Facets facets = state.query.facets();
        if (!state.archive && live(view)) {
            live.showLive(view);
            if (facets.view == view) return;
        } else if (state.archive) live.showLive(live(view) ? view : View.ITEMS);
        facets.view = view;
        ViewState<Facets, Sort> next = state.withQuery(state.query.withFacets(facets)).withPosition(view.name(), Collections.emptyList(), null, 0);
        saved.restore(state.archive || !live(view) ? next.withArchive(true) : next);
    }

    /**
     * The workspace switched between live and saved history (or the mode changed): relists the selector and selects the view in
     * front, the live dashboard's while live and the saved query's while saved. Live, the saved view's drill-downs leave ⋯.
     */
    void modeChanged(boolean archive) {
        if (saved == null) return;
        list(archive ? saved.state().query.facets().view : live.shownView(), archive);
        if (!archive) drills(Collections.<Component>emptyList());
    }

    /** A saved view was rendered (a choice, a restore, a route or a drill): the selector shows its view. */
    void rendered(ViewState<Facets, Sort> state) {
        if (saved != null && saved.state().archive) list(state.query.facets().view, true);
    }

    /** Puts the rendered saved view's drill-downs in the workspace ⋯, replacing the previous render's (an empty list clears them). */
    void drills(List<? extends Component> items) {
        if (saved != null) saved.filterBar().overflow().section(DRILLS).replace(items);
    }

    /** Simple's views, then Analyst's in Analyst, then {@code view} as "Current view" when neither lists it; silent. */
    private void list(View view, boolean archive) {
        ViewSelector<View> selector = live.selector();
        selector.setItems(SIMPLE, mode.analyst() ? ANALYST : Collections.<View>emptyList(), view);
        selector.select(view);
        caption.setVisible(archive && !live(view));
        lead.revalidate();
        lead.repaint();
    }
}
