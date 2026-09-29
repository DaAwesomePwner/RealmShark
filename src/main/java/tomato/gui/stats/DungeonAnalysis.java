package tomato.gui.stats;

import java.awt.Component;
import java.nio.file.Path;
import java.util.*;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewStateStore;
import tomato.gui.stats.LootQuery.*;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;

/**
 * The Dungeons tab's Analyst analysis (spec §6.3 Dungeons, user decision 2026-09-28: session comparison and A/B cohorts are
 * embedded in Dungeons): a saved-only archive workspace ({@link ArchiveWorkspace#savedOnly}) over the Statistics workspace's
 * dungeon views, unchanged — the dungeon loot profile, session comparison, dungeon statistics counters (activity-recorded
 * exits, never summed into visits), enemy hit events, loot by source and A/B cohorts — opening on the session comparison of
 * every session, as the cards count. No live view: it reads saved history only.
 *
 * <p>The workspace starts a read from its constructor, so the Dungeons tab builds it on the first Analysis show and closes it
 * with the tab. Its name, {@value #NAME}, keeps its filter row ({@code dungeon-analysis-filter-bar}), drawer preference and
 * saved view state ({@code ux.archive.dungeon-analysis}) apart from the cards' {@code dungeons} filter bar. Its loot numbers
 * use the Statistics views' join (session and visit ID with dungeon agreement), which may differ from the cards' "bags linked
 * to the exact run" on old histories; neither is adjusted to match the other.
 */
public final class DungeonAnalysis {
    /** The workspace's name: its component names, filter-bar key and saved view-state key. */
    public static final String NAME = "dungeon-analysis";
    /** The views the analysis offers, in the tabs' usual order. */
    public static final Set<View> VIEWS = Collections.unmodifiableSet(EnumSet.of(View.RATES, View.SESSIONS, View.COHORTS, View.COUNTERS,
        View.ENEMIES, View.SOURCES));

    private DungeonAnalysis() { }

    /** The first query: the session comparison of every saved session, newest first. */
    public static ArchiveQuery<Facets, Sort> initialQuery() { return LootQuery.initial(View.SESSIONS, SessionStore.ALL); }

    /**
     * A new saved-only analysis workspace over {@code store}, pinning into {@code scratch} and remembering its views in
     * {@code states} (the app's saved-view states). It starts reading at once: call it on the first Analysis show. EDT.
     */
    public static ArchiveWorkspace<Row, Facets, Sort> workspace(SessionStore store, Path scratch, ViewStateStore states) {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(scratch, "scratch"); Objects.requireNonNull(states, "states");
        return ArchiveWorkspace.savedOnly(store, NAME, new LootArchiveClient(scratch, VIEWS, initialQuery()), states);
    }

    /**
     * "Analyze" on a dungeon card: shows {@code canonical} (the card's exact canonical name, which the views compare with each
     * run's canonical name) as the analysis' only dungeon, in its current view (the session comparison when that view is not
     * offered), clearing any exact drill-down. Returns false, changing nothing, when {@code analysis} is not an analysis
     * workspace (a placeholder in tests). EDT.
     */
    public static boolean analyze(Component analysis, String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        if (!(analysis instanceof ArchiveWorkspace)) return false;
        ArchiveWorkspace<?, ?, ?> any = (ArchiveWorkspace<?, ?, ?>) analysis;
        if (!any.savedOnly() || !(any.state().query.facets() instanceof Facets)) return false;
        @SuppressWarnings("unchecked") ArchiveWorkspace<Row, Facets, Sort> workspace = (ArchiveWorkspace<Row, Facets, Sort>) any;
        ArchiveQuery<Facets, Sort> query = workspace.state().query;
        Facets facets = query.facets();
        facets.dungeons = new LinkedHashSet<>(Collections.singleton(canonical));
        facets.variant = facets.visitSession = facets.visitId = null;
        if (!VIEWS.contains(facets.view)) facets.view = View.SESSIONS;
        workspace.changeQuery(query.withFacets(facets));
        return true;
    }
}
