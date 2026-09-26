package tomato.gui.notifications;

import tomato.gui.maingui.AlertRuleEditor;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.realmshark.AlertRules;

/**
 * Route adapters for the alerts lane, for the coordinator to register with the shell navigator.
 * All methods run on the EDT. Payloads are detached values and never trigger a save.
 */
public final class AlertRouteTargets {
    private AlertRouteTargets() {}

    /**
     * {@link Destination#NOTIFICATIONS}: accepts no payload or a {@link NotificationFocus}.
     * {@code showPage} selects the Notifications shell page (e.g. {@code () -> shell.select(13)}).
     */
    public static RouteTarget notifications(NotificationsGUI page, Runnable showPage) {
        return new RouteTarget() {
            public Destination destination() { return Destination.NOTIFICATIONS; }
            @Override public boolean accepts(Route route) {
                return route.destination == Destination.NOTIFICATIONS && (route.payload == null || route.payload instanceof NotificationFocus);
            }
            public Object captureState() { return page.viewState(); }
            public void open(Route route) {
                showPage.run();
                NotificationFocus focus = (NotificationFocus)route.payload;
                Runnable back = () -> { if (Navigator.current().canGoBack()) Navigator.current().back(); };
                if (focus == null) return;
                if (focus.dungeon != null) page.focusDungeon(focus.dungeon, back);
                else page.selectSection(focus.section);
            }
            public void restoreState(Object state) { page.restoreViewState(state); }
        };
    }

    /**
     * {@link Destination#ALERT_DRAFT}: payload must be an {@link AlertRules.Draft}. Opens the modal draft
     * editor over the origin (which therefore stays on screen); nothing is saved, enabled or played.
     * Feature code may equally call {@link AlertRuleEditor#openDraft(AlertRules.Draft, Runnable)} directly.
     */
    public static RouteTarget alertDraft() {
        return new RouteTarget() {
            public Destination destination() { return Destination.ALERT_DRAFT; }
            @Override public boolean accepts(Route route) { return route.destination == Destination.ALERT_DRAFT && route.payload instanceof AlertRules.Draft; }
            public Object captureState() { return null; }
            public void open(Route route) { AlertRuleEditor.openDraft((AlertRules.Draft)route.payload, null); }
            public void restoreState(Object state) { }
        };
    }
}
