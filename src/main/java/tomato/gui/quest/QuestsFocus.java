package tomato.gui.quest;

/**
 * Route payload for {@code Destination.QUESTS}: the Quests tab a route brings forward. A plain Quests route (no payload) means
 * {@link #BOARD}. Immutable, so it is also the detached Back state {@link QuestsRouteTarget} captures.
 */
public enum QuestsFocus {
    /** The Board (tab id "captured"): the captured quests as cards or the table. Home's Quests card opens it. */
    BOARD,
    /** The Planner (tab id "plans"): quest plans and manual stock. Search's "Quest requirements and manual stock" opens it. */
    PLANNER
}
