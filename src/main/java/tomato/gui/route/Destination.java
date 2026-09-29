package tomato.gui.route;

/** Workspaces that can be opened by a typed {@link Route}. */
public enum Destination {
    RUNS, INSPECT, TIMELINE, RESOURCES, LOOT,
    ENCOUNTER, NOTIFICATIONS, ALERT_DRAFT, BRIDGE_REVIEW, LOGGING, MY_INFO, CHARACTERS, QUESTS, HOME, CHARACTER_SHEET,
    /** One exact saved run's recap (a {@code VisitRef}), a card on the Runs page (P5a). */
    RUN_RECAP
}
