package tomato.gui.route;

/**
 * Destination adapter registered with the navigator. All methods run on the EDT. {@code captureState}
 * returns a detached snapshot (query, page, selection, scroll) that {@code restoreState} applies
 * atomically; a restore must invalidate in-flight loads so stale completions cannot replace it.
 */
public interface RouteTarget {
    Destination destination();
    /** Whether this destination can honour the route's references; unsupported routes are rejected, not approximated. */
    default boolean accepts(Route route) { return route.destination == destination(); }
    Object captureState();
    void open(Route route);
    void restoreState(Object state);
    /**
     * Another route to open instead of an accepted one, or null (the default). The navigator follows it once, only when some
     * target accepts it, and keeps a single Back entry; otherwise this target opens the original route.
     */
    default Route redirect(Route route) { return null; }
}
