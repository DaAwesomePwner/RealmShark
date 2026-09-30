package tomato.backend.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who was in a logged area and when (docs/DPS-METERS.md, "Party outcomes"): players entering and leaving view, death
 * notices, your own death and nexus, and when the dungeon ended. Written on the packet thread only; published as copies.
 * Every event takes the next sequence number, which orders events exactly (several share one tick); times are capture
 * wall-clock milliseconds ({@code TomatoData.timePc}), -1 before the area's first tick. Plain data with no deserialization
 * hooks: {@code EncounterImport} reads it through its allow-list.
 */
public final class PresenceTimeline implements Serializable {
    private static final long serialVersionUID = 1L;
    /** How the end of the dungeon was observed, strongest first. */
    public static final String END_VICTORY = "victory", END_DIALOGUE = "dialogue", END_BOSS = "boss";

    /** A player entering ({@code present}) or leaving view; when leaving, the HP last seen (0 when unknown). */
    public static final class Change implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long seq, at;
        public final boolean present;
        public final int hp, maxHp;
        Change(long seq, long at, boolean present, int hp, int maxHp) {
            this.seq = seq; this.at = at; this.present = present; this.hp = hp; this.maxHp = maxHp;
        }
    }

    /** One object ID's presence; the name and class are the last known. */
    public static final class Player implements Serializable {
        private static final long serialVersionUID = 1L;
        public final int objectId;
        public String name;
        public int classType;
        public final ArrayList<Change> changes = new ArrayList<>();
        Player(int objectId) { this.objectId = objectId; }
        /** In view after its last change. */
        public boolean present() { return !changes.isEmpty() && changes.get(changes.size() - 1).present; }
    }

    /** A death notice: the name it carried (null when it could not be read) and the gravestone. */
    public static final class Death implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long seq, at;
        public final String name;
        public final int graveIcon;
        Death(long seq, long at, String name, int graveIcon) { this.seq = seq; this.at = at; this.name = name; this.graveIcon = graveIcon; }
    }

    /** A single observed moment with an optional detail (the killer, or how the end was observed). */
    public static final class Mark implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long seq, at;
        public final String detail;
        Mark(long seq, long at, String detail) { this.seq = seq; this.at = at; this.detail = detail; }
    }

    private long seq;
    private final HashMap<Integer, Player> players = new HashMap<>();
    private final ArrayList<Death> deaths = new ArrayList<>();
    private Integer localObjectId;
    private Mark localDeath, localEscape, end;

    /** A player object arrived in view (or is the local player); a repeated sighting only refreshes its name and class. */
    public void recordSeen(int objectId, String name, int classType, long at, boolean local) {
        Player player = players.computeIfAbsent(objectId, Player::new);
        if (name != null && !name.isEmpty()) player.name = name;
        if (classType > 0) player.classType = classType;
        if (local) localObjectId = objectId;
        if (!player.present()) player.changes.add(new Change(++seq, at, true, 0, 0));
    }

    /** A player object left view with the HP last seen; ignored when it is already out of view. */
    public void recordLeft(int objectId, int hp, int maxHp, long at) {
        Player player = players.computeIfAbsent(objectId, Player::new);
        if (player.changes.isEmpty() || player.present()) player.changes.add(new Change(++seq, at, false, hp, maxHp));
    }

    public void recordDeath(String name, int graveIcon, long at) {
        deaths.add(new Death(++seq, at, name == null || name.isEmpty() ? null : name, graveIcon));
    }

    /** Your own death (the DEATH packet); the first one stays. */
    public void recordLocalDeath(String killedBy, long at) {
        if (localDeath == null) localDeath = new Mark(++seq, at, killedBy);
    }

    /** You pressed nexus (the outgoing ESCAPE packet); the first one stays. */
    public void recordEscape(long at) {
        if (localEscape == null) localEscape = new Mark(++seq, at, null);
    }

    /**
     * The dungeon ended, observed as {@code source}: a stronger source replaces a weaker one, a later boss removal replaces
     * an earlier one, and the first victory or dialogue stays. Unknown sources are ignored.
     */
    public void recordEnd(String source, long at) {
        int rank = rank(source);
        if (rank == 0) return;
        int current = end == null ? 0 : rank(end.detail);
        if (rank > current || rank == current && END_BOSS.equals(source)) end = new Mark(++seq, at, source);
    }

    private static int rank(String source) {
        return END_VICTORY.equals(source) ? 3 : END_DIALOGUE.equals(source) ? 2 : END_BOSS.equals(source) ? 1 : 0;
    }

    /** A detached copy: later recording on either side does not reach the other. */
    public PresenceTimeline copy() {
        PresenceTimeline copy = new PresenceTimeline();
        copy.seq = seq;
        players.forEach((id, player) -> {
            Player detached = new Player(id);
            detached.name = player.name; detached.classType = player.classType; detached.changes.addAll(player.changes);
            copy.players.put(id, detached);
        });
        copy.deaths.addAll(deaths);
        copy.localObjectId = localObjectId; copy.localDeath = localDeath; copy.localEscape = localEscape; copy.end = end;
        return copy;
    }

    public Map<Integer, Player> players() { return Collections.unmodifiableMap(players); }
    public List<Death> deaths() { return Collections.unmodifiableList(deaths); }
    public Integer localObjectId() { return localObjectId; }
    public Mark localDeath() { return localDeath; }
    public Mark localEscape() { return localEscape; }
    public Mark end() { return end; }
}
