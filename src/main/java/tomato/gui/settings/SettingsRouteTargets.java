package tomato.gui.settings;

import java.util.List;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/** Settings and Notifications share a page: every target captures its section and the nested notification state. EDT only. */
public final class SettingsRouteTargets {
    private SettingsRouteTargets() {}
    private record SettingsRouteState(String section, Object notifications) {}

    public static List<RouteTarget> of(SettingsPage page, RouteTarget notifications) {
        abstract class PageTarget implements RouteTarget {
            public Object captureState() { return new SettingsRouteState(page.currentSection(), notifications.captureState()); }
            public void restoreState(Object state) {
                if (!(state instanceof SettingsRouteState saved)) throw new IllegalArgumentException("Not a Settings page state");
                notifications.restoreState(saved.notifications());
                page.showSection(saved.section());
            }
        }
        return List.of(new PageTarget() {
            public Destination destination() { return Destination.NOTIFICATIONS; }
            public boolean accepts(Route route) { return notifications.accepts(route); }
            public Route redirect(Route route) { return notifications.redirect(route); }
            public void open(Route route) { notifications.open(route); }
        }, new PageTarget() {
            public Destination destination() { return Destination.SETTINGS; }
            public boolean accepts(Route route) {
                return route.destination == destination() && route.query == null && route.visit == null && route.record == null
                    && route.recordingId == null && route.localObjectId == null && route.from == null && route.until == null
                    && (route.payload == null || route.payload instanceof SettingsFocus);
            }
            public void open(Route route) {
                if (!accepts(route)) throw new IllegalArgumentException("Unsupported Settings route: " + route);
                if (route.payload instanceof SettingsFocus focus) page.showSection(focus.section());
            }
        });
    }
}
