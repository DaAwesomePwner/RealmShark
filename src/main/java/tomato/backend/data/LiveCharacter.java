package tomato.backend.data;

import java.util.Objects;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.gui.myinfo.BuildEstimates;

/**
 * The character in game right now, for Home. The capture thread publishes detached snapshots from
 * {@link TomatoData#publishMyInfoPlayer} and clears them when the local identity resets (every map change, CREATE, HELLO,
 * account change) or capture stops, saying why ({@link Boundary}). Any thread may read; snapshots are immutable (arrays are
 * copied in and out).
 */
public final class LiveCharacter {
    /**
     * Why the last published character stopped being current. TRANSIENT: a map change, reconnect or connection reset that
     * may keep the same account and character (Home rides it out briefly). IDENTITY: another account or character. STOPPED:
     * capture stopped.
     */
    public enum Boundary { TRANSIENT, IDENTITY, STOPPED }

    private static final StatType[] TOTALS = {StatType.MAX_HP_STAT, StatType.MAX_MP_STAT, StatType.ATTACK_STAT,
        StatType.DEFENSE_STAT, StatType.SPEED_STAT, StatType.DEXTERITY_STAT, StatType.VITALITY_STAT, StatType.WISDOM_STAT};
    private static final StatType[] BOOSTS = {StatType.MAX_HP_BOOST_STAT, StatType.MAX_MP_BOOST_STAT, StatType.ATTACK_BOOST_STAT,
        StatType.DEFENSE_BOOST_STAT, StatType.SPEED_BOOST_STAT, StatType.DEXTERITY_BOOST_STAT, StatType.VITALITY_BOOST_STAT, StatType.WISDOM_BOOST_STAT};
    private static final StatType[] SLOTS = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};

    /**
     * Stat arrays use the canonical order life, mana, atk, def, spd, dex, vit, wis with -1 = not captured; {@code equipment}
     * is weapon, ability, armor, ring (a value > 0 is an item id, otherwise the slot is empty). A null array or value was
     * not captured. {@code account} is the journal's hashed account key; {@code build} holds Build's inputs detached at
     * publish time (null = none), estimated on the reader's thread with {@code build().estimate()}; {@code observedAt} is
     * the publish time (epoch ms).
     */
    public record Snapshot(String account, int characterId, int classId, String name, Integer skin, Integer level, Long characterFame,
                           int[] totals, int[] base, int[] equipment, Integer accountFame, Integer gold, Integer rankStars,
                           int[] exaltBonus, BuildEstimates.Inputs build, long observedAt) {
        public Snapshot {
            totals = copy(totals, 8, "totals"); base = copy(base, 8, "base");
            equipment = copy(equipment, 4, "equipment"); exaltBonus = copy(exaltBonus, 8, "exaltBonus");
        }
        @Override public int[] totals() { return copy(totals); }
        @Override public int[] base() { return copy(base); }
        @Override public int[] equipment() { return copy(equipment); }
        @Override public int[] exaltBonus() { return copy(exaltBonus); }
        private static int[] copy(int[] values) { return values == null ? null : values.clone(); }
        private static int[] copy(int[] values, int length, String name) {
            if (values != null && values.length != length) throw new IllegalArgumentException(name + " must have " + length + " entries");
            return copy(values);
        }
    }

    private Snapshot current, lastKnown;
    private Boundary boundary;
    private boolean accepting = true;
    private long revision, lastSeenAt;

    /** Capture thread: the local character's latest state. */
    public synchronized void publish(Snapshot value) {
        if (value == null) throw new IllegalArgumentException("Use clear() when no character is in game");
        if (!accepting) return; // A producer may finish detaching after capture stop was requested.
        current = value; lastKnown = value; revision++;
    }
    /**
     * Capture thread (or capture stop): no character is in game, because of {@code why}. Only an actual change bumps the
     * revision: clearing the current character, or a lasting reason (IDENTITY, STOPPED) replacing a TRANSIENT one when the
     * account or character turns out to have changed after a map change already cleared it. lastSeenAt stays the first clear.
     */
    public synchronized void clear(long at, Boundary why) {
        Objects.requireNonNull(why, "why");
        if (current != null) { current = null; lastSeenAt = at; boundary = why; revision++; }
        else if (lastKnown != null && boundary == Boundary.TRANSIENT && why != Boundary.TRANSIENT) { boundary = why; revision++; }
    }
    /**
     * Capture thread: the local identity reset to {@code account} and {@code characterId} (null or negative = not known yet).
     * TRANSIENT unless a part known on both sides differs from the last published character: then IDENTITY.
     */
    public synchronized void reset(long at, String account, int characterId) {
        Snapshot last = lastKnown;
        boolean other = last != null && (account != null && last.account() != null && !account.equals(last.account())
            || characterId >= 0 && last.characterId() >= 0 && characterId != last.characterId());
        clear(at, other ? Boundary.IDENTITY : Boundary.TRANSIENT);
    }
    /** Start follows termination of the previous capture worker, so no old publication can cross this boundary. */
    public synchronized void start() { accepting = true; }
    /** Atomically reject late producer publications and clear the current character. */
    public synchronized void stop(long at) { accepting = false; clear(at, Boundary.STOPPED); }
    public synchronized long revision() { return revision; }
    /** The character in game now, or null. */
    public synchronized Snapshot current() { return current; }
    /** The last snapshot published during this app run, or null; kept across clears. */
    public synchronized Snapshot lastKnown() { return lastKnown; }
    /** When a published character last stopped being current (epoch ms); 0 until that first happens. */
    public synchronized long lastSeenAt() { return lastSeenAt; }
    /** Why the last published character stopped being current; null until that first happens. */
    public synchronized Boundary lastBoundary() { return boundary; }

    /** Capture thread only: a snapshot of the capture-owned local player entity; the estimates wait for a reader. */
    static Snapshot read(String account, int characterId, Entity player, BuildEstimates.Inputs build, long observedAt) {
        int[] totals = new int[8], base = new int[8];
        for (int i = 0; i < 8; i++) {
            Integer total = value(player, TOTALS[i]), boost = value(player, BOOSTS[i]);
            totals[i] = total == null ? -1 : total;
            base[i] = total == null || boost == null ? -1 : Math.max(0, total - boost);
        }
        int[] equipment = new int[4];
        for (int i = 0; i < 4; i++) {
            Integer item = value(player, SLOTS[i]);
            if (item == null) { equipment = null; break; }
            equipment[i] = item;
        }
        String name = player.getStatName() == null ? "" : player.getStatName().split(",", 2)[0].trim();
        Integer fame = value(player, StatType.CURR_FAME_STAT);
        return new Snapshot(account, characterId, player.objectType, name.isEmpty() ? null : name,
            value(player, StatType.SKIN_ID), value(player, StatType.LEVEL_STAT), fame == null ? null : fame.longValue(),
            totals, base, equipment, value(player, StatType.FAME_STAT), value(player, StatType.CREDITS_STAT),
            value(player, StatType.NUM_STARS_STAT), CharacterJournal.exaltBonus(player), build, observedAt);
    }
    private static Integer value(Entity player, StatType type) {
        StatData value = player.stat.get(type);
        return value == null ? null : value.statValue;
    }
}
