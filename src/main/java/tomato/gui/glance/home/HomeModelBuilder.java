package tomato.gui.glance.home;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import packets.data.QuestData;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.*;
import tomato.gui.dps.MeterSummary;
import tomato.gui.glance.home.HomeModel.State;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.DisplayValue;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.myinfo.BuildEstimates;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseDungeon;
import tomato.realmshark.enums.CharacterClass;

/** Pure, static builders for Home's sections. No Swing (DisplayValue only); called on the refresh threads. */
public final class HomeModelBuilder {
    /** A live snapshot cleared by a transient boundary (a map change clears it briefly) counts as live this long after its clear. */
    static final long MAP_CHANGE_GRACE_MILLIS = 5_000;
    /** Quest lists a day old are stale even while capture continues: dailies reset. */
    static final long QUEST_LIST_STALE_MILLIS = 24L * 60 * 60 * 1000;
    static final int METER_ROWS = 3, NEEDS_SHOWN = 3, QUEST_LINES = 3, REASON_LIMIT = 160;
    static final String[] STAT_LABELS = {"LIFE", "MANA", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"};
    static final String FAME_UNKNOWN = "Enter the game with capture on to read character fame";
    static final String DPS_UNKNOWN = "Enter the game with capture on to estimate weapon damage";
    static final String MP_UNKNOWN = "Enter the game with capture on to estimate MP recovery";
    private static final String POTION_RULE = "Potions needed: Life and Mana (cap - base) / 5 rounded up; other stats cap - base.";
    private static final IntFunction<int[]> CLASS_CAPS = classId -> {
        int[] caps = CharacterClass.getStats(classId);   // the shared definition array: never hand it out
        return caps == null ? null : caps.clone();
    };
    private static final IntFunction<String> CLASS_NAMES = CharacterClass::getName;

    private HomeModelBuilder() {}

    /** Short canonical stat label: LIFE, MANA, ATT, DEF, SPD, DEX, VIT, WIS (the character sheet uses Home's wording). */
    public static String statLabel(int index) { return STAT_LABELS[index]; }

    /**
     * {@code live} is LiveCharacter.current() with {@code lastSeenAt} 0, or (not in game) LiveCharacter.lastKnown() with
     * LiveCharacter.lastSeenAt() and {@code boundary}, LiveCharacter.lastBoundary(); see {@link #stillCurrent}.
     * {@code estimates} are Build's estimates from {@code live}'s detached inputs (null = none). {@code last} is the journal's
     * most recent character; {@code account} the saved record of the shown character's account.
     */
    public static HomeModel.Hero hero(LiveCharacter.Snapshot live, BuildEstimates.Estimates estimates, CharacterJournal.CharacterRecord last,
                                      CharacterJournal.AccountRecord account, long lastSeenAt, LiveCharacter.Boundary boundary, long now) {
        return hero(live, estimates, last, account, lastSeenAt, boundary, now, CLASS_CAPS, CLASS_NAMES);
    }

    static HomeModel.Hero hero(LiveCharacter.Snapshot live, BuildEstimates.Estimates estimates, CharacterJournal.CharacterRecord last,
                               CharacterJournal.AccountRecord account, long lastSeenAt, LiveCharacter.Boundary boundary, long now,
                               IntFunction<int[]> caps, IntFunction<String> names) {
        if (live != null) return fromLive(live, estimates, account, stillCurrent(lastSeenAt, boundary, now), lastSeenAt, caps, names);
        if (last != null) return fromJournal(last, account, caps, names);
        return HomeModel.Hero.placeholder(State.EMPTY, "No character captured yet. Start capture and enter the game to see your character.");
    }

    /**
     * True while the live character is in game ({@code lastSeenAt} 0) or was cleared by a TRANSIENT boundary at most
     * MAP_CHANGE_GRACE_MILLIS ago; a capture stop, another account or character, or an unknown reason ends it at once.
     */
    public static boolean stillCurrent(long lastSeenAt, LiveCharacter.Boundary boundary, long now) {
        return lastSeenAt <= 0 || boundary == LiveCharacter.Boundary.TRANSIENT && now - lastSeenAt <= MAP_CHANGE_GRACE_MILLIS;
    }

    /** A live hero carries no packet time: an unchanged character builds an equal Hero, so Home does not redraw it. */
    private static HomeModel.Hero fromLive(LiveCharacter.Snapshot live, BuildEstimates.Estimates estimates, CharacterJournal.AccountRecord account,
                                           boolean current, long lastSeenAt, IntFunction<int[]> caps, IntFunction<String> names) {
        int classId = live.classId();
        String className = className(classId, names);
        int[] classCaps = caps.apply(classId), cap = HomeModel.sized(classCaps, 8), base = HomeModel.sized(live.base(), 8);
        int[] need = potionsNeeded(base, cap);
        int maxed = maxed(need), exalt = exaltTiers(account, classId);
        long seen = current ? 0 : lastSeenAt;
        String when = current ? "" : DisplayFormat.formatTimestamp(seen);
        Long fameValue = live.characterFame();
        DisplayValue fame = fameValue == null ? DisplayValue.unknown(FAME_UNKNOWN)
            : current ? DisplayValue.count(fameValue, "Live character stats", FAME_UNKNOWN)
            : DisplayValue.stale(DisplayFormat.formatInteger(fameValue.longValue()), "Last seen " + when);
        String basis = current ? "Estimated from your current build" : "Estimated from the build last seen " + when;
        String evidence = (current ? "Live character stats from the game." : "Not in game. Showing the last captured character, last seen " + when + ".")
            + " Caps from class definitions" + (classCaps == null ? " (not loaded: caps and potions unknown)" : "") + ". " + POTION_RULE
            + " Exalt tiers from saved account exalts" + (exalt < 0 ? " (not captured yet)" : "") + ". Weapon DPS and MP/sec are estimates, not measurements.";
        Double dps = estimates == null ? null : estimates.weaponDps(), mp = estimates == null ? null : estimates.mpPerSecond();
        return new HomeModel.Hero(current ? State.LIVE : State.STALE, name(live.name(), className, live.characterId()), classId, className,
            live.skin(), live.level(), fame, maxed, base, cap, live.totals(), need, needsLine(need, maxed), exalt, equipment(live.equipment()),
            estimate(dps, 0, basis + " with the Build page's method; not a recorded measurement", DPS_UNKNOWN),
            estimate(mp, 1, basis + " with the Build page's method", MP_UNKNOWN),
            // A live hero carries no stale label, so saved values may fill the account line only on a stale one (spec §1).
            accountLine(live.rankStars(), live.accountFame(), live.gold(), current ? null : account), seen, evidence, live.journalKey());
    }

    private static HomeModel.Hero fromJournal(CharacterJournal.CharacterRecord last, CharacterJournal.AccountRecord account,
                                              IntFunction<int[]> caps, IntFunction<String> names) {
        int classId = last.classId;
        String className = last.className != null && !last.className.isEmpty() ? last.className : className(classId, names);
        int[] base = HomeModel.sized(null, 8), cap = HomeModel.sized(caps.apply(classId), 8), slots = HomeModel.sized(null, 4);
        if (last.stats != null) for (int i = 0; i < Math.min(8, last.stats.length); i++) if (last.stats[i] != null) base[i] = last.stats[i];
        if (last.equipment != null) for (int i = 0; i < Math.min(4, last.equipment.length); i++)
            if (last.equipment[i] != null) slots[i] = last.equipment[i] > 0 ? last.equipment[i] : 0;
        int[] need = potionsNeeded(base, cap);
        int maxed = maxed(need), exalt = exaltTiers(account, classId);
        String when = last.lastSeen > 0 ? DisplayFormat.formatTimestamp(last.lastSeen) : "at an unknown time";
        DisplayValue fame = last.fame == null ? DisplayValue.unknown(FAME_UNKNOWN)
            : DisplayValue.stale(DisplayFormat.formatInteger(last.fame.longValue()), "Saved in the character journal, last seen " + when);
        String evidence = "Not in game. Showing the saved character journal record, last seen " + when + ". Live totals and estimates return when you "
            + "enter the game with capture on. " + POTION_RULE + " Exalt tiers from saved account exalts" + (exalt < 0 ? " (not captured yet)" : "") + ".";
        return new HomeModel.Hero(State.STALE, name(last.name, className, last.characterId), classId, className, last.skin, last.level,
            fame, maxed, base, cap, null, need, needsLine(need, maxed), exalt, slots, DisplayValue.unknown(DPS_UNKNOWN),
            DisplayValue.unknown(MP_UNKNOWN), accountLine(null, null, null, account), last.lastSeen, evidence, sheetKey(last.key));
    }

    private static final Pattern SHEET_KEY = Pattern.compile("[0-9a-f]{64}:[0-9]+");
    /** A journal key the character sheet accepts ("<64 hex>:<characterId>"), else null: the hero then opens the Characters list. */
    static String sheetKey(String key) { return key != null && SHEET_KEY.matcher(key).matches() ? key : null; }
    public static int[] potionsNeeded(int[] base, int[] caps) {
        int[] need = new int[8];
        for (int i = 0; i < 8; i++) need[i] = base[i] < 0 || caps[i] < 0 ? -1 : CharacterJournal.potions(base[i], caps[i], i);   // -1 = unknown
        return need;
    }
    public static int maxed(int[] need) { int count = 0; for (int n : need) { if (n < 0) return -1; if (n == 0) count++; } return count; }
    static String needsLine(int[] need, int maxed) {
        if (maxed < 0 || maxed == 8) return "";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 8; i++) if (need[i] > 0) parts.add(STAT_LABELS[i] + " " + need[i]);
        int more = parts.size() - NEEDS_SHOWN;
        return "Needs " + String.join(" · ", parts.subList(0, Math.min(NEEDS_SHOWN, parts.size()))) + " potions" + (more > 0 ? " · +" + more + " more" : "");
    }
    /** Exalt arrays are in RealmCharacter order (dex, spd, vit, wis, def, atk, mana, life); EXALT_ORDER maps canonical stats into them. */
    static int exaltTiers(CharacterJournal.AccountRecord account, int classId) {
        int[] counts = account == null || account.exalts == null ? null : account.exalts.get(classId);
        if (counts == null || counts.length != 8) return -1;
        int tiers = 0;
        for (int i = 0; i < 8; i++) {
            int count = counts[CharacterJournal.EXALT_ORDER[i]];
            if (count < 0) return -1;
            tiers += CharacterJournal.exaltLevel(count);
        }
        return tiers;
    }
    static int[] equipment(int[] live) {
        int[] slots = HomeModel.sized(null, 4);   // null array = not captured: every slot unknown
        if (live != null) for (int i = 0; i < Math.min(4, live.length); i++) slots[i] = live[i] > 0 ? live[i] : 0;
        return slots;
    }
    /** Live values first, then the saved account record (null for a live hero: live values only); unknown parts are omitted. */
    static String accountLine(Integer stars, Integer fame, Integer gold, CharacterJournal.AccountRecord account) {
        Long s = stars != null ? Long.valueOf(stars) : account != null && account.rankStars != null ? Long.valueOf(account.rankStars) : null;
        Long f = fame != null ? Long.valueOf(fame) : account != null ? account.accountFame : null;
        Long g = gold != null ? Long.valueOf(gold) : account != null ? account.gold : null;
        List<String> parts = new ArrayList<>();
        // Words, not a star glyph: the default Segoe UI body font has no ★.
        if (s != null) parts.add(DisplayFormat.formatInteger(s.longValue()) + (s == 1 ? " star" : " stars"));
        if (f != null) parts.add(DisplayFormat.formatInteger(f.longValue()) + " account fame");
        if (g != null) parts.add(DisplayFormat.formatInteger(g.longValue()) + " gold");
        return String.join(" · ", parts);
    }
    private static DisplayValue estimate(Double value, int decimals, String how, String whyUnknown) {
        return value == null || !Double.isFinite(value) ? DisplayValue.unknown(whyUnknown) : DisplayValue.estimate(DisplayFormat.formatNumber(value, decimals), how);
    }
    private static String className(int classId, IntFunction<String> names) { String name = names.apply(classId); return name != null && !name.isEmpty() ? name : "Class #" + classId; }
    private static String name(String name, String className, int characterId) { return name != null && !name.isBlank() ? name : className + " #" + characterId; }

    /**
     * {@code meter} is the live meter's summary and {@code meterContext} the encounter it measures. The meter rows are shown
     * only when that encounter was entered in exactly the current visit (spec §1: exact links only); otherwise Now shows the
     * area and capture state only.
     */
    public static HomeModel.Now now(boolean capturing, DiscoveryLog.CurrentVisit visit, EncounterContext meterContext, MeterSummary meter,
                                    KeypopGUI.LastPop pop) {
        MeterSummary summary = meter != null && linked(visit == null ? null : visit.visit, meterContext) ? meter : MeterSummary.EMPTY;
        return now(capturing, visit == null ? null : visit.map, visit == null || visit.started <= 0 ? null : visit.started,
            summary.top(), summary.localRank(), summary.players(), pop);
    }
    /** True when the live meter's encounter belongs to exactly this visit (VisitRef equality, never map name or time). */
    static boolean linked(VisitRef visit, EncounterContext meterContext) {
        return visit != null && meterContext != null && visit.equals(meterContext.visit);
    }
    static HomeModel.Now now(boolean capturing, String area, Long startedAt, List<MeterSummary.Row> top, int localRank, int players, KeypopGUI.LastPop pop) {
        if (!capturing) return new HomeModel.Now(State.EMPTY, false, null, null, List.of(), 0, 0, pop);
        if (area == null || !ParseDungeon.isDungeon(area)) return new HomeModel.Now(State.LIVE, true, area, null, List.of(), 0, 0, pop);
        List<MeterSummary.Row> rows = top == null ? List.of() : top.subList(0, Math.min(METER_ROWS, top.size()));
        return new HomeModel.Now(State.LIVE, true, area, startedAt, rows, Math.max(0, localRank), Math.max(0, players), pop);
    }

    public static HomeModel.Today today(HomeArchive.Window window, HomeArchive.Result result, Exception failure) {
        if (failure != null) return new HomeModel.Today(State.UNAVAILABLE, window, null, "Saved history could not be read: " + oneLine(failure));
        if (result == null) return HomeModel.Today.placeholder(State.LOADING, window);
        HomeArchive.Totals t = result.totals();
        // Unreadable sessions may hold this period's records: never "nothing recorded" while any exist.
        boolean empty = t.unreadableSessions() == 0 && t.runsEntered() == 0 && t.fameGained() == null
            && t.untiered() + t.setTiered() + t.whiteBags() + t.potions() == 0;
        return new HomeModel.Today(empty ? State.EMPTY : State.LIVE, window, t, !empty ? ""
            : window == HomeArchive.Window.TODAY ? "Nothing recorded today yet. Enter a dungeon with capture on."
            : "Nothing recorded this session yet. Enter a dungeon with capture on.");
    }

    public static HomeModel.Runs runs(HomeArchive.Result result, Exception failure) {
        if (failure != null) return HomeModel.Runs.placeholder(State.UNAVAILABLE, "Saved runs could not be read: " + oneLine(failure));
        if (result == null) return HomeModel.Runs.placeholder(State.LOADING, "");
        // Saved sessions that could not be read may hold newer runs: say so above the rows instead of implying the list is complete.
        String skipped = result.unreadableRecent() == 0 ? "" : TodayTiles.unreadableText(result.unreadableRecent());
        if (result.recent().isEmpty() && skipped.isEmpty())
            return HomeModel.Runs.placeholder(State.EMPTY, "No dungeon runs recorded yet. Enter a dungeon with capture on.");
        return new HomeModel.Runs(State.LIVE, result.recent(), skipped);
    }

    /** A re-read failed after a good read of the same window (read at {@code readAt}): keep its totals, labeled stale with age and reason (spec §7). */
    public static HomeModel.Today staleToday(HomeArchive.Window window, HomeArchive.Result lastGood, long readAt, Throwable failure, long now) {
        return new HomeModel.Today(State.STALE, window, lastGood.totals(), lastUpdated(readAt, now, failure));
    }
    /** As staleToday, for the recent runs of that last good read. */
    public static HomeModel.Runs staleRuns(HomeArchive.Result lastGood, long readAt, Throwable failure, long now) {
        return new HomeModel.Runs(State.STALE, lastGood.recent(), lastUpdated(readAt, now, failure));
    }
    static String lastUpdated(long readAt, long now, Throwable failure) { return "Last updated " + ago(readAt, now) + " · " + oneLine(failure); }

    /** Pinned open, then pinned done quests (at most 3; spec §6.1 shows pinned quests only); counts over the whole list. */
    public static HomeModel.Quests quests(ProgressionData.Snapshot snapshot, Predicate<QuestData> pinned, long now) {
        if (snapshot == null || snapshot.quests == null) return HomeModel.Quests.placeholder(State.EMPTY);
        Predicate<QuestData> isPinned = pinned == null ? quest -> false : pinned;
        int pins = 0, repeatable = 0, done = 0;
        List<QuestData> pinnedOpen = new ArrayList<>(), pinnedDone = new ArrayList<>();
        for (QuestData quest : snapshot.quests.rows()) {
            boolean pin = isPinned.test(quest);
            if (pin) pins++;
            if (quest.repeatable) repeatable++;
            if (quest.completed) done++;
            if (pin) (quest.completed ? pinnedDone : pinnedOpen).add(quest);
        }
        List<HomeModel.QuestLine> top = new ArrayList<>();
        for (List<QuestData> group : List.of(pinnedOpen, pinnedDone))
            for (QuestData quest : group) if (top.size() < QUEST_LINES)
                top.add(new HomeModel.QuestLine(quest.name != null && !quest.name.isBlank() ? quest.name : "Unnamed quest", quest.rewards, quest.repeatable, quest.completed));
        long capturedAt = snapshot.quests.capturedAt;
        boolean stale = !snapshot.currentQuests() || now - capturedAt >= QUEST_LIST_STALE_MILLIS;
        return new HomeModel.Quests(stale ? State.STALE : State.LIVE, pins, repeatable, done, top, capturedAt, stale);
    }

    /** First line of the failure message (or its type), whitespace collapsed, at most REASON_LIMIT characters. */
    static String oneLine(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.isBlank()) message = failure == null ? "Unknown error" : failure.getClass().getSimpleName();
        String line = message.strip().split("\\R", 2)[0].replaceAll("\\s+", " ");
        return line.length() <= REASON_LIMIT ? line : line.substring(0, REASON_LIMIT - 1) + "…";
    }

    /** KitFormat.relative's wording against an explicit clock: "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago", a date. */
    static String ago(long at, long now) {
        long minutes = Math.max(0, now - at) / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " h ago";
        long days = hours / 24;
        if (days == 1) return "yesterday";
        if (days < 7) return days + " days ago";
        return DisplayFormat.DATE.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
    }
}
