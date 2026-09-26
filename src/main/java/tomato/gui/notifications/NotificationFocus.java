package tomato.gui.notifications;

import tomato.gui.keypop.KeypopGUI;

/**
 * Detached {@link tomato.gui.route.Route#payload} for {@link tomato.gui.route.Destination#NOTIFICATIONS}.
 * Opening a focus only changes what Notifications shows; it never changes an alert choice.
 */
public final class NotificationFocus {
    /** Tab title such as "Key-pops", or null to keep the current tab. */
    public final String section;
    /** Exact notification dungeon name to focus, or null. */
    public final String dungeon;
    /** Recorded alert decision ID to select in Recent decisions, or null. */
    public final Long decision;

    private NotificationFocus(String section, String dungeon, Long decision) {
        this.section = section; this.dungeon = dungeon; this.decision = decision;
    }
    public static NotificationFocus section(String section) { return new NotificationFocus(section, null, null); }
    /** Focus an exact dungeon; resolve it first with {@link #resolveDungeon(String)}. */
    public static NotificationFocus dungeon(String exactName) { return new NotificationFocus(NotificationsGUI.KEY_POPS, exactName, null); }
    public static NotificationFocus decision(long id) { return new NotificationFocus(NotificationsGUI.DECISIONS, null, id); }

    /**
     * Returns the observed name only when it is exactly a known notification dungeon. Runes, vials,
     * incs, unknown portals and near-miss spellings return null: callers must say so rather than guess.
     */
    public static String resolveDungeon(String observed) {
        return observed != null && KeypopGUI.getDungeonNames().contains(observed) ? observed : null;
    }
    @Override public String toString() { return "NotificationFocus{" + section + (dungeon == null ? "" : " dungeon") + (decision == null ? "" : " decision=" + decision) + "}"; }
}
