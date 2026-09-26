package tomato.gui.activity;

import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import tomato.history.link.VisitRef;

import java.time.ZoneId;
import java.util.Collections;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure route/query helpers for Runs, Inspect, Timeline and Resources. Every visit link is the exact origin
 * session plus the recorded journal visit ID; names, map titles and nearest timestamps are never used.
 */
public final class ActivityRoutes {
    private ActivityRoutes() { }
    /** Default investigation radius around a moment: the half-open window [t - 30 s, t + 30 s). */
    public static final long AROUND_MILLIS = 30_000L;
    private static final Pattern SESSION = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    /** Whether the reference can be expressed as an archive query (a saved session ID plus a visit ID). */
    public static boolean queryable(VisitRef ref) {
        return ref != null && SESSION.matcher(ref.sessionId).matches() && !ref.visitId.isEmpty();
    }

    /** Reference for a saved visit row: the row's origin session and its recorded visit ID, or null. */
    public static VisitRef reference(String session, String visitId) {
        if (session == null || session.isEmpty() || visitId == null || visitId.isEmpty()) return null;
        VisitRef ref = new VisitRef(session, visitId);
        return queryable(ref) ? ref : null;
    }

    /**
     * The exact-visit query: the session + visit ID facet applied before paging, with no review-queue facets,
     * text or date bounds that could hide the linked visit. The order is kept. The scope is All Sessions: the
     * facet still admits only rows whose origin session and recorded visit ID both match, while a reference
     * to an imported, deleted or unsaved session yields zero matches (an explicit unavailable state) instead
     * of a failed read of a missing session.
     */
    public static ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> visitQuery(
            ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> base, VisitRef ref) {
        if (!queryable(ref)) throw new IllegalArgumentException("Visit reference is not a saved session visit");
        ActivityQueries.Filters filters = new ActivityQueries.Filters();
        filters.visitSession = ref.sessionId; filters.visitId = ref.visitId;
        return base.withScope(SessionStore.ALL).withText("").withBounds(ArchiveQuery.Bounds.all()).withFacets(filters);
    }

    /**
     * A Timeline window: exact half-open bounds [from, until) on event time, unknown times excluded, and the
     * exact visit facet when a reference is supplied. Displayed rows and every export of the resulting
     * workspace use this same query, so they contain the same events.
     */
    public static ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> timelineWindow(
            ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> base, VisitRef ref, long from, long until, ZoneId zone) {
        if (until <= from) throw new IllegalArgumentException("Expected from < until");
        ArchiveQuery.Bounds bounds = new ArchiveQuery.Bounds(from, until, zone == null ? ZoneId.systemDefault() : zone, ArchiveQuery.TimeMode.ENTRY, false);
        ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> query = ref == null ? base.withFacets(new ActivityQueries.Filters()) : visitQuery(base, ref);
        return query.withBounds(bounds).withOrder(Collections.singletonList(new ArchiveQuery.Order<>(ActivityQueries.Sort.TIME, ArchiveQuery.Direction.ASCENDING)));
    }

    /** Route to Timeline around one moment of an exact visit; the window is [moment - radius, moment + radius). */
    public static Route timelineAround(VisitRef ref, long moment, long radius) {
        if (radius <= 0) throw new IllegalArgumentException("Radius must be positive");
        return Route.to(Destination.TIMELINE).withVisit(ref).withBounds(moment - radius, moment + radius);
    }

    /** Exact-visit route; null when the reference is missing or not a saved session visit. */
    public static Route visit(Destination destination, VisitRef ref) {
        return queryable(ref) ? Route.to(destination).withVisit(ref) : null;
    }

    /**
     * Why a route cannot be offered, or null when the installed navigator accepts it. EDT only. The text is
     * shown next to disabled actions so an unavailable handoff is explained rather than silently hidden.
     */
    public static String unavailable(Route route, String what) {
        if (route == null) return what + " unavailable: no exact saved visit reference";
        Navigator navigator = Navigator.current();
        if (navigator == Navigator.NONE) return what + " unavailable: navigation is not installed in this window";
        return navigator.canOpen(route) ? null : what + " unavailable: that view cannot resolve this exact visit here";
    }

    static boolean exactVisit(ActivityQueries.Filters f) {
        return f != null && !Objects.toString(f.visitId, "").isEmpty() && !Objects.toString(f.visitSession, "").isEmpty();
    }
}
