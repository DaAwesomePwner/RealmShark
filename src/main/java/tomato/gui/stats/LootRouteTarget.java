package tomato.gui.stats;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewState;
import tomato.gui.route.*;
import tomato.gui.stats.LootQuery.*;
import tomato.history.archive.ArchiveQuery;
import tomato.history.link.VisitRef;

/**
 * Route adapter for the saved Loot (and Statistics) workspaces; the coordinator registers it. EDT only.
 * A LOOT route with an exact {@link VisitRef} opens that run's saved item occurrences scoped to its origin
 * session. A route carrying a Loot/Statistics query opens that query. Record, recording and local-object
 * references are not resolvable here and are rejected rather than approximated.
 */
public final class LootRouteTarget implements RouteTarget {
    private final Destination destination;
    private final Supplier<ViewState<Facets,Sort>> capture;
    private final Consumer<ArchiveQuery<Facets,Sort>> open;
    private final Consumer<ViewState<Facets,Sort>> restore;

    public LootRouteTarget(Destination destination, Supplier<ViewState<Facets,Sort>> capture,
                           Consumer<ArchiveQuery<Facets,Sort>> open, Consumer<ViewState<Facets,Sort>> restore) {
        if (destination != Destination.LOOT && destination != Destination.STATISTICS) throw new IllegalArgumentException("Loot or Statistics only");
        this.destination = destination; this.capture = Objects.requireNonNull(capture);
        this.open = Objects.requireNonNull(open); this.restore = Objects.requireNonNull(restore);
    }
    /** {@code restore} is the coordinator's atomic workspace restore (for example {@code workspace::restore}). */
    public static LootRouteTarget forWorkspace(Destination destination, ArchiveWorkspace<Row,Facets,Sort> workspace,
                                               Consumer<ViewState<Facets,Sort>> restore) {
        // Opening applies the routed query atomically with a cleared selection and scroll anchor, as the Activity
        // targets do, so an old selection from another query is never carried into the destination.
        return new LootRouteTarget(destination, workspace::state, query -> {
            ViewState<Facets,Sort> current = workspace.state();
            restore.accept(current.withQuery(query).withArchive(true).withPosition(current.tab, java.util.Collections.emptyList(), null, 0));
        }, restore);
    }

    @Override public Destination destination() { return destination; }
    @Override public boolean accepts(Route route) {
        if (route == null || route.destination != destination || route.record != null || route.recordingId != null || route.localObjectId != null) return false;
        if (route.query != null && !(route.query.facets() instanceof Facets)) return false;
        if (route.visit != null && (destination != Destination.LOOT || !route.visit.sessionId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) return false;
        return route.visit != null || route.query != null;
    }
    @Override public Object captureState() { return capture.get(); }
    @Override @SuppressWarnings("unchecked") public void open(Route route) {
        if (!accepts(route)) throw new IllegalArgumentException("Unsupported " + destination + " route: " + route);
        open.accept(query(route, capture.get().query));
    }
    @Override @SuppressWarnings("unchecked") public void restoreState(Object state) { restore.accept((ViewState<Facets,Sort>)state); }

    /** Pure translation used by {@link #open}; exposed for tests. */
    @SuppressWarnings("unchecked")
    static ArchiveQuery<Facets,Sort> query(Route route, ArchiveQuery<Facets,Sort> current) {
        ArchiveQuery<Facets,Sort> q = route.query != null ? (ArchiveQuery<Facets,Sort>)route.query : current;
        if (route.from != null || route.until != null)
            q = q.withBounds(new ArchiveQuery.Bounds(route.from, route.until, java.time.ZoneId.of(q.bounds().zone), ArchiveQuery.TimeMode.ENTRY, false));
        if (route.visit != null) {
            Facets f = q.facets(); f.view = View.OCCURRENCES; f.variant = null;
            f.visitSession = route.visit.sessionId; f.visitId = route.visit.visitId; f.validate();
            // All Sessions + the exact session/visit facet: an absent (imported, deleted or unsaved) session yields
            // an explicit zero-match state instead of a failed read of a missing session scope.
            q = q.withScope(tomato.history.SessionStore.ALL).withFacets(f);
            if (route.from == null && route.until == null) q = q.withBounds(ArchiveQuery.Bounds.all());
        }
        q.facets().validate();
        return q;
    }
}
