package tomato.gui.route;

import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewState;
import tomato.history.archive.ArchiveQuery;

import java.util.Collections;
import java.util.Objects;

/**
 * Generic adapter for a typed saved-history workspace. It honours only a destination and an optional
 * query of the workspace's exact facet/sort types; routes carrying visit, record, recording, bounds or
 * payload references are rejected so a module-specific target can resolve them instead. Capture and
 * restore use the workspace's detached {@link ViewState} (scope, query, page, selection, scroll anchor).
 */
public final class ArchiveRouteTarget<R, F, S extends Enum<S>> implements RouteTarget {
    private final Destination destination;
    private final ArchiveWorkspace<R, F, S> workspace;

    public ArchiveRouteTarget(Destination destination, ArchiveWorkspace<R, F, S> workspace) {
        this.destination = Objects.requireNonNull(destination);
        this.workspace = Objects.requireNonNull(workspace);
    }

    @Override public Destination destination() { return destination; }

    @Override public boolean accepts(Route route) {
        if (route.destination != destination || route.visit != null || route.record != null || route.recordingId != null
            || route.localObjectId != null || route.from != null || route.until != null || route.payload != null) return false;
        return route.query == null || typed(route.query) != null;
    }

    @Override public Object captureState() { return workspace.state(); }

    @Override public void open(Route route) {
        if (route.query == null) return; // Showing the page is the whole request.
        ArchiveQuery<F, S> query = typed(route.query);
        if (query == null) throw new IllegalArgumentException("Route query does not match this workspace");
        ViewState<F, S> current = workspace.state();
        workspace.restore(current.withQuery(query).withArchive(true).withPosition(current.tab, Collections.emptyList(), null, 0));
    }

    @Override @SuppressWarnings("unchecked") public void restoreState(Object state) {
        if (!(state instanceof ViewState)) throw new IllegalArgumentException("Not a saved-history view state");
        workspace.restore((ViewState<F, S>) state); // The workspace validates the query's exact types.
    }

    /** The same query re-read through this workspace's types, or null when its types differ. */
    private ArchiveQuery<F, S> typed(ArchiveQuery<?, ?> query) {
        try {
            ArchiveQuery<F, S> typed = workspace.state().query.restore(query.toJson());
            return typed.equals(query) ? typed : null;
        } catch (RuntimeException incompatible) { return null; }
    }
}
