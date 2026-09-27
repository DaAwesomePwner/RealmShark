package tomato.gui.glance.home;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import tomato.gui.dps.MeterSummary;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.DisplayValue;

/**
 * Immutable Home view model: built off the EDT, swapped in on the EDT. Per-stat arrays have 8 entries in canonical
 * order (life, mana, atk, def, spd, dex, vit, wis) with -1 for unknown; equipment has 4 slots (weapon, ability,
 * armor, ring): item id > 0, 0 empty, -1 unknown. Sections compare by content (arrays by value), so HomeRefresher can
 * keep an unchanged section's previous object and HomePage can skip it.
 */
public record HomeModel(Hero hero, Now now, Today today, Runs runs, Quests quests) {
    public enum State { LOADING, EMPTY, LIVE, STALE, UNAVAILABLE }

    /** Every section loading: what Home shows until the first refresh arrives. */
    public static final HomeModel LOADING = new HomeModel(Hero.placeholder(State.LOADING, ""), Now.placeholder(State.LOADING),
        Today.placeholder(State.LOADING, HomeArchive.Window.TODAY), Runs.placeholder(State.LOADING, ""), Quests.placeholder(State.LOADING));

    public HomeModel {
        Objects.requireNonNull(hero, "hero"); Objects.requireNonNull(now, "now"); Objects.requireNonNull(today, "today");
        Objects.requireNonNull(runs, "runs"); Objects.requireNonNull(quests, "quests");
    }

    /** {@code maxed} 0-8 or -1; {@code exaltTiers} sum over 8 stats or -1; {@code lastSeenAt} when a STALE character was last seen (epoch ms), 0 while LIVE. */
    public record Hero(State state, String name, int classId, String className, Integer skin, Integer level,
                       DisplayValue fame, int maxed, int[] base, int[] caps, int[] totals, int[] potionsNeeded,
                       String needsLine, int exaltTiers, int[] equipment, DisplayValue weaponDps, DisplayValue mpPerSecond,
                       String accountLine, long lastSeenAt, String evidence) {
        public Hero {
            Objects.requireNonNull(state, "state");
            fame = orUnknown(fame); weaponDps = orUnknown(weaponDps); mpPerSecond = orUnknown(mpPerSecond);
            base = sized(base, 8); caps = sized(caps, 8); totals = sized(totals, 8); potionsNeeded = sized(potionsNeeded, 8);
            equipment = sized(equipment, 4);
            needsLine = text(needsLine); accountLine = text(accountLine); evidence = text(evidence);
        }
        @Override public int[] base() { return base.clone(); }  @Override public int[] caps() { return caps.clone(); }
        @Override public int[] totals() { return totals.clone(); }  @Override public int[] potionsNeeded() { return potionsNeeded.clone(); }
        @Override public int[] equipment() { return equipment.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Hero h && state == h.state && classId == h.classId && maxed == h.maxed && exaltTiers == h.exaltTiers
                && lastSeenAt == h.lastSeenAt && Objects.equals(name, h.name) && Objects.equals(className, h.className)
                && Objects.equals(skin, h.skin) && Objects.equals(level, h.level) && fame.equals(h.fame) && Arrays.equals(base, h.base)
                && Arrays.equals(caps, h.caps) && Arrays.equals(totals, h.totals) && Arrays.equals(potionsNeeded, h.potionsNeeded)
                && needsLine.equals(h.needsLine) && Arrays.equals(equipment, h.equipment) && weaponDps.equals(h.weaponDps)
                && mpPerSecond.equals(h.mpPerSecond) && accountLine.equals(h.accountLine) && evidence.equals(h.evidence);
        }
        @Override public int hashCode() {
            return Objects.hash(state, name, classId, level, fame, maxed, Arrays.hashCode(base), Arrays.hashCode(totals), Arrays.hashCode(equipment), lastSeenAt);
        }
        /** No character data (loading, empty or unavailable): every value unknown; {@code evidence} says why. */
        public static Hero placeholder(State state, String evidence) {
            return new Hero(state, null, -1, null, null, null, null, -1, null, null, null, null, "", -1, null, null, null, "", 0, evidence);
        }
    }
    /** {@code area} null = no active visit; outside dungeons only area and capture state are filled; {@code localRank} 0 = not on the meter. */
    public record Now(State state, boolean capturing, String area, Long startedAt, List<MeterSummary.Row> top,
                      int localRank, int players, KeypopGUI.LastPop lastPop) {
        public Now { Objects.requireNonNull(state, "state"); top = top == null ? List.of() : List.copyOf(top); }
        public static Now placeholder(State state) { return new Now(state, false, null, null, List.of(), 0, 0, null); }
    }
    /** {@code totals} is set in LIVE and EMPTY; {@code reason} is the one-line EMPTY/UNAVAILABLE text. */
    public record Today(State state, HomeArchive.Window window, HomeArchive.Totals totals, String reason) {
        public Today { Objects.requireNonNull(state, "state"); Objects.requireNonNull(window, "window"); reason = text(reason); }
        public static Today placeholder(State state, HomeArchive.Window window) { return new Today(state, window, null, ""); }
    }
    public record Runs(State state, List<HomeArchive.RecentRun> rows, String reason) {
        public Runs { Objects.requireNonNull(state, "state"); rows = rows == null ? List.of() : List.copyOf(rows); reason = text(reason); }
        public static Runs placeholder(State state, String reason) { return new Runs(state, List.of(), reason); }
    }
    public record QuestLine(String name, int[] rewardIds, boolean repeatable, boolean done) {
        public QuestLine { name = text(name); rewardIds = rewardIds == null ? new int[0] : rewardIds.clone(); }
        @Override public int[] rewardIds() { return rewardIds.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof QuestLine q && name.equals(q.name) && Arrays.equals(rewardIds, q.rewardIds) && repeatable == q.repeatable && done == q.done;
        }
        @Override public int hashCode() { return Objects.hash(name, Arrays.hashCode(rewardIds), repeatable, done); }
    }
    /** Counts are meaningful in LIVE and STALE only. */
    public record Quests(State state, int pinned, int repeatable, int done, List<QuestLine> top, long capturedAt, boolean stale) {
        public Quests { Objects.requireNonNull(state, "state"); top = top == null ? List.of() : List.copyOf(top); }
        public static Quests placeholder(State state) { return new Quests(state, 0, 0, 0, List.of(), 0, false); }
    }

    public HomeModel withHero(Hero hero) { return new HomeModel(hero, now, today, runs, quests); }
    public HomeModel withNow(Now now) { return new HomeModel(hero, now, today, runs, quests); }
    public HomeModel withToday(Today today) { return new HomeModel(hero, now, today, runs, quests); }
    public HomeModel withRuns(Runs runs) { return new HomeModel(hero, now, today, runs, quests); }
    public HomeModel withQuests(Quests quests) { return new HomeModel(hero, now, today, runs, quests); }

    /** A copy of {@code values} with exactly {@code length} entries; missing entries are -1 (unknown). */
    static int[] sized(int[] values, int length) {
        int[] result = new int[length];
        Arrays.fill(result, -1);
        if (values != null) System.arraycopy(values, 0, result, 0, Math.min(length, values.length));
        return result;
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static DisplayValue orUnknown(DisplayValue value) { return value == null ? DisplayValue.unknown("") : value; }
}
