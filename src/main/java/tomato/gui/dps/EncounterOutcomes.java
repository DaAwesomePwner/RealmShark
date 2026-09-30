package tomato.gui.dps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.backend.data.PresenceTimeline;
import tomato.gui.dps.shared.DeathParser;

/**
 * Whether each player of a recording completed the dungeon, died, nexused, is still in progress, or is unknown
 * (docs/DPS-METERS.md, "Party outcomes"). Built from the recording's {@link PresenceTimeline}; players are grouped by name,
 * since an account name is unique in the game and a reconnect gives the same player a new object ID. A recording made before
 * outcome tracking has no timeline: only a death notice naming a unique player is shown. Immutable; build it on any thread
 * from copies.
 */
public final class EncounterOutcomes {
    /** Sort order in the meter: completed first, unknown last. */
    public enum Kind { COMPLETED, PENDING, NEXUSED, DIED, UNKNOWN }
    /** AVAILABLE: the end was seen. PENDING: live, not ended yet. UNSEEN: saved, the end was never seen. UNAVAILABLE: no timeline. */
    public enum State { AVAILABLE, PENDING, UNSEEN, UNAVAILABLE }

    static final String LEGACY = "Recorded before outcome tracking: only a death notice naming this player can be shown.";
    static final String PENDING_REASON = "The dungeon has not ended yet; outcomes are decided when it does.";
    static final String UNSEEN_REASON = "The end of the dungeon was not seen (you left first, or it sent no victory, final-boss line or boss removal).";
    static final String NOT_SEEN = "Not seen entering this area (capture started or restarted mid-dungeon).";

    /** One player's outcome. {@code toString} is the label, so the meter's sorter and copy show it. */
    public static final class Outcome implements Comparable<Outcome> {
        public final Kind kind;
        /** Milliseconds from the encounter start, or null when unknown. */
        public final Long atMs;
        /** Whole-percent HP when the player left view, or null. */
        public final Integer hpPercent;
        /** Gravestone object type for a death, or -1. */
        public final int graveIcon;
        /** Your own death only: what killed you, when the server said. */
        public final String killedBy;
        /** Observed directly (your own nexus, death or presence), not inferred from someone leaving view. */
        public final boolean confirmed;
        /** One sentence saying how the outcome was decided. */
        public final String reason;

        Outcome(Kind kind, Long atMs, Integer hpPercent, int graveIcon, String killedBy, boolean confirmed, String reason) {
            this.kind = kind; this.atMs = atMs; this.hpPercent = hpPercent; this.graveIcon = graveIcon;
            this.killedBy = killedBy; this.confirmed = confirmed; this.reason = reason;
        }

        public String label() {
            switch (kind) {
                case COMPLETED: return "Completed";
                case PENDING: return "In progress";
                case DIED: return atMs == null ? "Died" : "Died " + clock(atMs);
                case NEXUSED: {
                    StringBuilder text = new StringBuilder("Nexused");
                    if (atMs != null) text.append(' ').append(clock(atMs));
                    if (hpPercent != null) text.append(" · ").append(hpPercent).append("% HP");
                    return text.toString();
                }
                default: return "Unknown";
            }
        }

        /** Died or nexused: the player is marked as not having completed the dungeon. */
        public boolean didNotComplete() { return kind == Kind.DIED || kind == Kind.NEXUSED; }

        @Override public int compareTo(Outcome other) {
            int byKind = Integer.compare(kind.ordinal(), other.kind.ordinal());
            if (byKind != 0) return byKind;
            return Long.compare(atMs == null ? Long.MAX_VALUE : atMs, other.atMs == null ? Long.MAX_VALUE : other.atMs);
        }

        @Override public String toString() { return label(); }
    }

    /** One player of the party list: the name shown, the class and the outcome. */
    public static final class Line {
        public final String name;
        public final int classType;
        public final Outcome outcome;
        Line(String name, int classType, Outcome outcome) { this.name = name; this.classType = classType; this.outcome = outcome; }
    }

    private static final Outcome LEGACY_UNKNOWN = new Outcome(Kind.UNKNOWN, null, null, -1, null, false, LEGACY);
    private static final Outcome NOT_SEEN_OUTCOME = new Outcome(Kind.UNKNOWN, null, null, -1, null, false, NOT_SEEN);
    private static final EncounterOutcomes NONE = new EncounterOutcomes(State.UNAVAILABLE, Collections.emptyMap(), Collections.emptyList(), 0);

    private final State state;
    private final Map<Integer, Outcome> byId;
    private final List<Line> lines;
    private final int players, completed, nexused, died, unknown, pending;

    private EncounterOutcomes(State state, Map<Integer, Outcome> byId, List<Line> lines, int unmatchedDeaths) {
        this.state = state;
        this.byId = byId;
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing((Line line) -> line.outcome).thenComparing(line -> line.name, String.CASE_INSENSITIVE_ORDER));
        this.lines = Collections.unmodifiableList(sorted);
        int c = 0, n = 0, d = unmatchedDeaths, u = 0, p = 0;
        for (Line line : sorted) {
            switch (line.outcome.kind) {
                case COMPLETED: c++; break;
                case NEXUSED: n++; break;
                case DIED: d++; break;
                case PENDING: p++; break;
                default: u++;
            }
        }
        players = sorted.size() + unmatchedDeaths; completed = c; nexused = n; died = d; unknown = u; pending = p;
    }

    /** No players and no timeline. */
    public static EncounterOutcomes none() { return NONE; }

    /**
     * Outcomes of one recording. {@code startedAt} is its first tick ({@code DpsData.dungeonStartTime}); {@code live} marks
     * an encounter still being recorded. {@code players} and {@code notices} are read only when {@code presence} is null.
     */
    public static EncounterOutcomes of(PresenceTimeline presence, long startedAt, boolean live,
                                       Collection<Entity> players, Collection<NotificationPacket> notices) {
        return presence == null ? legacy(players, notices) : observed(presence, startedAt, live);
    }

    /** Distinct player characters with recorded damage on these enemies, first seen first. */
    public static List<Entity> playersOf(Collection<Entity> enemies) {
        Map<Integer, Entity> players = new LinkedHashMap<>();
        if (enemies != null) for (Entity enemy : enemies) {
            if (enemy == null) continue;
            for (Damage hit : enemy.getPlayerDamageList()) if (hit != null && hit.owner != null) players.putIfAbsent(hit.owner.id, hit.owner);
        }
        return new ArrayList<>(players.values());
    }

    private static EncounterOutcomes observed(PresenceTimeline timeline, long startedAt, boolean live) {
        PresenceTimeline.Mark end = timeline.end();
        List<PresenceTimeline.Player> ordered = new ArrayList<>(timeline.players().values());
        ordered.sort(Comparator.comparingInt(player -> player.objectId));
        Map<String, List<PresenceTimeline.Player>> groups = new LinkedHashMap<>();
        for (PresenceTimeline.Player player : ordered)
            groups.computeIfAbsent(player.name == null ? "#" + player.objectId : "n:" + player.name, key -> new ArrayList<>()).add(player);
        Map<String, PresenceTimeline.Death> deaths = new HashMap<>();
        int unmatched = 0;
        for (PresenceTimeline.Death death : timeline.deaths()) {
            if (death.name != null && groups.containsKey("n:" + death.name)) deaths.putIfAbsent(death.name, death);
            else if (before(death.seq, end)) unmatched++;
        }
        Integer local = timeline.localObjectId();
        Map<Integer, Outcome> byId = new HashMap<>();
        List<Line> lines = new ArrayList<>();
        for (List<PresenceTimeline.Player> group : groups.values()) {
            String name = group.get(0).name;
            boolean mine = false;
            int classType = 0;
            List<PresenceTimeline.Change> changes = new ArrayList<>();
            for (PresenceTimeline.Player player : group) {
                mine |= local != null && player.objectId == local;
                if (player.classType > 0) classType = player.classType;
                changes.addAll(player.changes);
            }
            changes.sort(Comparator.comparingLong(change -> change.seq));
            Outcome outcome = decide(timeline, end, mine, name == null ? null : deaths.get(name), changes, startedAt, live);
            for (PresenceTimeline.Player player : group) byId.put(player.objectId, outcome);
            lines.add(new Line(name == null ? "Unknown player" : name, classType, outcome));
        }
        State state = end != null ? State.AVAILABLE : live ? State.PENDING : State.UNSEEN;
        return new EncounterOutcomes(state, byId, lines, unmatched);
    }

    private static Outcome decide(PresenceTimeline timeline, PresenceTimeline.Mark end, boolean mine, PresenceTimeline.Death death,
                                  List<PresenceTimeline.Change> changes, long startedAt, boolean live) {
        PresenceTimeline.Mark localDeath = mine ? timeline.localDeath() : null, escape = mine ? timeline.localEscape() : null;
        int grave = death == null ? -1 : death.graveIcon;
        if (localDeath != null && before(localDeath.seq, end)) {
            Long at = since(localDeath.at, startedAt);
            return new Outcome(Kind.DIED, at, null, grave, localDeath.detail, true,
                "You died" + when(at) + (localDeath.detail == null ? "." : ", killed by " + localDeath.detail + "."));
        }
        if (death != null && before(death.seq, end)) {
            Long at = since(death.at, startedAt);
            return new Outcome(Kind.DIED, at, null, grave, null, false, "A death notice named this player" + when(at) + ".");
        }
        if (escape != null && before(escape.seq, end)) {
            Long at = since(escape.at, startedAt);
            return new Outcome(Kind.NEXUSED, at, null, -1, null, true, "You pressed nexus" + when(at) + ".");
        }
        if (end == null) return live ? pending(changes, startedAt) : unknown(UNSEEN_REASON);
        if (changes.isEmpty()) return NOT_SEEN_OUTCOME;
        if (changes.get(0).seq > end.seq) return unknown("First seen after the dungeon ended.");
        PresenceTimeline.Change last = changes.get(changes.size() - 1);
        if (!last.present && last.seq < end.seq) {
            Long at = since(last.at, startedAt);
            Integer hp = last.maxHp > 0 ? (int) Math.round(100.0 * Math.max(0, Math.min(last.hp, last.maxHp)) / last.maxHp) : null;
            return new Outcome(Kind.NEXUSED, at, hp, -1, null, false, "Left view" + when(at) + (hp == null ? "" : " at " + hp + "% HP")
                + " and did not return before the dungeon ended: a nexus, a disconnect, or out of view when it ended.");
        }
        return new Outcome(Kind.COMPLETED, since(end.at, startedAt), null, -1, null, mine,
            "In the dungeon when it ended (" + endLabel(end.detail) + ").");
    }

    private static Outcome pending(List<PresenceTimeline.Change> changes, long startedAt) {
        PresenceTimeline.Change last = changes.isEmpty() ? null : changes.get(changes.size() - 1);
        String reason = last != null && !last.present
            ? "Left view" + when(since(last.at, startedAt)) + "; decided when the dungeon ends." : PENDING_REASON;
        return new Outcome(Kind.PENDING, null, null, -1, null, false, reason);
    }

    private static EncounterOutcomes legacy(Collection<Entity> players, Collection<NotificationPacket> notices) {
        List<Entity> people = players == null ? Collections.emptyList() : new ArrayList<>(players);
        Map<String, Integer> names = new HashMap<>();
        for (Entity player : people) { String name = name(player); if (name != null) names.merge(name, 1, Integer::sum); }
        Map<String, NotificationPacket> deaths = new HashMap<>();
        if (notices != null) for (NotificationPacket notice : new ArrayList<>(notices)) {
            String name = notice == null ? null : DeathParser.extractName(notice);
            if (name != null && !name.isEmpty()) deaths.putIfAbsent(name, notice);
        }
        Map<Integer, Outcome> byId = new HashMap<>();
        List<Line> lines = new ArrayList<>();
        for (Entity player : people) {
            if (player == null) continue;
            String name = name(player);
            NotificationPacket death = name != null && names.get(name) == 1 ? deaths.get(name) : null;
            Outcome outcome = death == null ? LEGACY_UNKNOWN : new Outcome(Kind.DIED, null, null, death.pictureType, null, false,
                "A death notice names this player; recorded before outcome tracking, so no time is known.");
            byId.put(player.id, outcome);
            lines.add(new Line(name == null ? "Unknown player" : name, player.objectType, outcome));
        }
        return new EncounterOutcomes(State.UNAVAILABLE, byId, lines, 0);
    }

    public Outcome outcome(int objectId) {
        Outcome outcome = byId.get(objectId);
        return outcome != null ? outcome : state == State.UNAVAILABLE ? LEGACY_UNKNOWN : NOT_SEEN_OUTCOME;
    }

    public Outcome outcome(Entity player) { return player == null ? outcome(Integer.MIN_VALUE) : outcome(player.id); }

    public State state() { return state; }
    /** One line per player, completed first, then by name. */
    public List<Line> lines() { return lines; }
    public int players() { return players; }
    public int completed() { return completed; }
    public int nexused() { return nexused; }
    public int died() { return died; }
    public int unknown() { return unknown; }
    public int pending() { return pending; }

    /** "8 players · 5 completed · 2 nexused · 1 died", or the pending, unseen or unavailable form. */
    public String summary() {
        if (state == State.UNAVAILABLE) return "Outcomes unavailable (recorded before this feature)";
        StringBuilder text = new StringBuilder().append(players).append(players == 1 ? " player" : " players");
        if (state == State.AVAILABLE) text.append(" · ").append(completed).append(" completed");
        if (nexused > 0) text.append(" · ").append(nexused).append(" nexused");
        if (died > 0) text.append(" · ").append(died).append(" died");
        if (state == State.PENDING) text.append(" · outcome pending");
        else if (state == State.UNSEEN) text.append(" · end of dungeon not seen");
        else if (unknown > 0) text.append(" · ").append(unknown).append(" unknown");
        return text.toString();
    }

    private static boolean before(long seq, PresenceTimeline.Mark end) { return end == null || seq < end.seq; }

    private static Long since(long at, long startedAt) { return at < 0 || startedAt <= 0 ? null : Math.max(0L, at - startedAt); }

    private static String when(Long at) { return at == null ? "" : " at " + clock(at); }

    static String clock(long ms) {
        long seconds = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String endLabel(String source) {
        if (PresenceTimeline.END_VICTORY.equals(source)) return "server victory";
        if (PresenceTimeline.END_DIALOGUE.equals(source)) return "final-boss dialogue";
        if (PresenceTimeline.END_BOSS.equals(source)) return "last boss removed";
        return "end observed";
    }

    private static Outcome unknown(String reason) { return new Outcome(Kind.UNKNOWN, null, null, -1, null, false, reason); }

    private static String name(Entity player) {
        String name = player == null ? null : player.name();
        return name == null || name.isEmpty() ? null : name;
    }
}
