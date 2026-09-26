package tomato.gui.logging;

import packets.packetcapture.logger.DiscoveryCatalog;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

import java.util.Objects;

/**
 * Error route into Logging. A gameplay view that suspects missing evidence opens
 * {@code Route.to(Destination.LOGGING).withPayload(LoggingRouteTarget.issuesFor(view))}; Logging then shows
 * the packet issues relevant to that view through the catalog's reviewed allowlist. Only packet names from
 * that allowlist are accepted; no values, chat, credentials or raw packets travel in the route. EDT only.
 */
public final class LoggingRouteTarget implements RouteTarget {
    private final LoggingGUI logging;

    public LoggingRouteTarget(LoggingGUI logging) { this.logging = Objects.requireNonNull(logging); }

    /** Detached, allowlisted focus: the affected view and optionally one of its packets. */
    public static final class Focus {
        public final Destination view;
        public final String packet;
        private Focus(Destination view, String packet) { this.view = view; this.packet = packet; }
        boolean valid() {
            java.util.Set<String> allowed = DiscoveryCatalog.packetsFor(view.name());
            return !allowed.isEmpty() && (packet == null || allowed.contains(packet));
        }
    }
    /** Packet issues (decode failures, trailing bytes) for every allowlisted input of {@code view}. */
    public static Focus issuesFor(Destination view) { return new Focus(Objects.requireNonNull(view), null); }
    /** One allowlisted input packet of {@code view}; rejected by {@link #accepts} when not allowlisted. */
    public static Focus packetFor(Destination view, String packet) { return new Focus(Objects.requireNonNull(view), Objects.requireNonNull(packet)); }

    @Override public Destination destination() { return Destination.LOGGING; }
    @Override public boolean accepts(Route route) {
        if (route.destination != Destination.LOGGING || route.query != null || route.visit != null || route.record != null
            || route.recordingId != null || route.localObjectId != null || route.from != null || route.until != null) return false;
        return route.payload == null || route.payload instanceof Focus && ((Focus) route.payload).valid();
    }
    @Override public Object captureState() { return logging.captureViewState(); }
    @Override public void open(Route route) {
        if (!accepts(route)) throw new IllegalArgumentException("Unsupported Logging route: " + route);
        if (route.payload == null) return;
        Focus focus = (Focus) route.payload;
        LoggingViewState.Fields state = logging.captureViewState();
        LoggingViewState.Tab packets = state.tabs.get("packets");
        LoggingQuery query = new LoggingQuery();
        if (focus.packet != null) query.packet = focus.packet; else query.issues = true;
        packets.query = query; packets.selection = null;
        state.tab = "packets";
        logging.applyViewState(state);
    }
    @Override public void restoreState(Object state) {
        if (!(state instanceof LoggingViewState.Fields)) throw new IllegalArgumentException("Not a Logging view state");
        logging.applyViewState((LoggingViewState.Fields) state);
    }
}
