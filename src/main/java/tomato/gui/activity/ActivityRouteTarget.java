package tomato.gui.activity;

import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewState;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.history.archive.ArchiveQuery;

import javax.swing.JComponent;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Exact-visit destination for the saved Runs, Inspect, Timeline and Resources workspaces. A routed visit is
 * applied as the origin session scope plus the exact session + visit ID facet (before paging), so consecutive
 * visits with the same dungeon name can never resolve to each other. An unresolved reference (imported,
 * deleted or not yet saved) opens an explicit unavailable state rather than a nearby visit. Timeline also
 * honours half-open bounds. Capture and restore use the workspace's detached {@link ViewState}. EDT only.
 */
public final class ActivityRouteTarget implements RouteTarget {
    private static final Set<Destination> SUPPORTED = EnumSet.of(Destination.RUNS, Destination.INSPECT, Destination.TIMELINE, Destination.RESOURCES);
    private final Destination destination;
    private final ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace;

    public ActivityRouteTarget(Destination destination, ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace) {
        if (!SUPPORTED.contains(Objects.requireNonNull(destination))) throw new IllegalArgumentException("Unsupported activity destination " + destination);
        this.destination = destination; this.workspace = Objects.requireNonNull(workspace);
    }

    /**
     * Target for a shell component created by {@link ActivityPanel#workspace} or {@code SecurityGUI.workspace}, or
     * null when the component is a live-only view (no saved history store) that cannot resolve saved visits.
     */
    @SuppressWarnings("unchecked")
    public static ActivityRouteTarget of(Destination destination, JComponent component) {
        if (!(component instanceof ArchiveWorkspace)) return null;
        ArchiveWorkspace<?, ?, ?> candidate = (ArchiveWorkspace<?, ?, ?>) component;
        ArchiveQuery<?, ?> query = candidate.state().query;
        try {
            if (!ActivityQueries.initial().restore(query.toJson()).equals(query)) return null;
        } catch (RuntimeException otherTypes) { return null; }
        return new ActivityRouteTarget(destination, (ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort>) candidate);
    }

    @Override public Destination destination() { return destination; }

    @Override public boolean accepts(Route route) {
        if (route.destination != destination || route.record != null || route.recordingId != null || route.localObjectId != null || route.payload != null) return false;
        if (route.query != null) return false; // A visit or window route carries its own exact query.
        boolean bounded = route.from != null || route.until != null;
        if (destination == Destination.TIMELINE) {
            if (bounded && (route.from == null || route.until == null || route.until <= route.from)) return false;
            if (route.visit != null && !ActivityRoutes.queryable(route.visit)) return false;
            return route.visit != null || bounded;
        }
        return !bounded && ActivityRoutes.queryable(route.visit);
    }

    @Override public Object captureState() { return workspace.state(); }

    @Override public void open(Route route) {
        if (!accepts(route)) throw new IllegalArgumentException("Route is not an exact visit or window");
        ViewState<ActivityQueries.Filters, ActivityQueries.Sort> current = workspace.state();
        ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> base = ActivityQueries.initial();
        ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> query;
        if (route.from != null) {
            query = ActivityRoutes.timelineWindow(base, route.visit, route.from, route.until, java.time.ZoneId.of(current.query.bounds().zone));
            if (route.visit == null) query = query.withScope(current.query.scope()); // Window across the current scope.
        } else query = ActivityRoutes.visitQuery(base, route.visit);
        // Selection and scroll are cleared: the destination selects the single exact match itself.
        workspace.restore(current.withQuery(query).withArchive(true).withPosition(current.tab, Collections.emptyList(), null, 0));
    }

    @Override @SuppressWarnings("unchecked") public void restoreState(Object state) {
        if (!(state instanceof ViewState)) throw new IllegalArgumentException("Not a saved-history view state");
        workspace.restore((ViewState<ActivityQueries.Filters, ActivityQueries.Sort>) state);
    }
}
