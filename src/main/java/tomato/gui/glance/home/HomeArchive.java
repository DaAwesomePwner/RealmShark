package tomato.gui.glance.home;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.dps.RecordedEncounter;
import tomato.gui.stats.LootFacts;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseDungeon;

/**
 * Home's Today / This session totals and newest dungeon runs, read from saved history. Off the EDT only
 * ({@link SessionStore#catalog()} throws there). One catalog listing per read serves every module read. Loot and DPS join
 * runs by exact {@link VisitRef} equality only.
 */
public final class HomeArchive {
    public enum Window { TODAY, SESSION }

    /**
     * {@code fameGained} null = no fame readings in the window; {@code fameSeries} is then empty, else 12 cumulative buckets.
     * {@code lootRecorded} is false when no loot bag was saved in the window's sessions: the loot counts are then unknown, not zero.
     */
    public record Totals(Window window, long from, long until, int runsCompleted, int runsEntered,
                         Long fameGained, Double famePerHour, double[] fameSeries,
                         int untiered, int setTiered, int whiteBags, int potions, boolean lootRecorded) {
        public Totals {
            Objects.requireNonNull(window, "window");
            fameSeries = fameSeries == null ? new double[0] : fameSeries.clone();
        }
        @Override public double[] fameSeries() { return fameSeries.clone(); }
        /** Content equality (the series by value), so an unchanged re-read is recognized as unchanged. */
        @Override public boolean equals(Object other) {
            return other instanceof Totals t && window == t.window && from == t.from && until == t.until && runsCompleted == t.runsCompleted
                && runsEntered == t.runsEntered && Objects.equals(fameGained, t.fameGained) && Objects.equals(famePerHour, t.famePerHour)
                && Arrays.equals(fameSeries, t.fameSeries) && untiered == t.untiered && setTiered == t.setTiered && whiteBags == t.whiteBags
                && potions == t.potions && lootRecorded == t.lootRecorded;
        }
        @Override public int hashCode() {
            return Objects.hash(window, from, until, runsCompleted, runsEntered, fameGained, famePerHour, Arrays.hashCode(fameSeries),
                untiered, setTiered, whiteBags, potions, lootRecorded);
        }
    }
    /** {@code ended} null while in progress; {@code localDps} only from a recording linked to exactly this visit. */
    public record RecentRun(VisitRef visit, String map, String outcome, long started, Long ended,
                            List<Integer> lootIds, Double localDps) {
        public RecentRun {
            Objects.requireNonNull(visit, "visit");
            lootIds = lootIds == null ? List.of() : List.copyOf(lootIds);
        }
    }
    public record Result(Totals totals, List<RecentRun> recent) {
        public Result {
            Objects.requireNonNull(totals, "totals");
            recent = recent == null ? List.of() : List.copyOf(recent);
        }
    }

    static final int RECENT = 5, LOOT_ICONS = 8, BUCKETS = 12;
    /** Fame per hour needs at least ten minutes of readings (summed over sessions). */
    static final long RATE_MINIMUM_MILLIS = 10 * 60_000L;
    private static final Comparator<Candidate> NEWEST_FIRST = Comparator.comparingLong((Candidate c) -> c.visit().started).reversed()
        .thenComparing(c -> c.session().id).thenComparing(c -> c.visit().id);

    private HomeArchive() {}

    public static Result read(SessionStore store, Window window, long now, ZoneId zone, List<RecordedEncounter> recordings) throws IOException {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(window, "window"); Objects.requireNonNull(zone, "zone");
        List<SessionStore.SessionEntry> catalog = store.catalog();   // listed once; every module read below reuses it
        List<SessionStore.Session> sessions = new ArrayList<>();   // newest start first, as catalog() sorts them
        for (SessionStore.SessionEntry entry : catalog) {
            // Missing metadata cannot establish whether this session overlaps Today or contains a newer run.
            // Reject the combined read so the refresher retains prior results as stale, rather than showing partial totals.
            if (!entry.readable()) throw new IOException("Unreadable session " + entry.id + ": " + entry.error);
            sessions.add(entry.session());
        }
        Cache cache = new Cache(store, catalog);
        Totals totals;
        if (window == Window.TODAY) {
            LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            long from = day.atStartOfDay(zone).toInstant().toEpochMilli(), until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            List<SessionStore.Session> inBounds = new ArrayList<>();
            for (SessionStore.Session session : sessions) if (overlaps(session, cache.end(session), from, until)) inBounds.add(session);
            totals = totals(cache, inBounds, new Span(window, from, until, from, until, Math.min(now, until)));
        } else {
            long from = store.started();   // every record of the current session counts; no time filter
            totals = totals(cache, List.of(current(catalog, store.currentId())),
                new Span(window, from, Math.max(from, now), Long.MIN_VALUE, Long.MAX_VALUE, now));
        }
        return new Result(totals, recent(cache, sessions, recordings == null ? List.of() : recordings));
    }

    /** Same completion rule as ActivityJournal.Visit.runStatus(); the labels the Runs page shows. */
    static ActivityQueries.Outcome outcome(ActivityJournal.Visit visit) { return ActivityQueries.visit(visit).outcome; }

    private static SessionStore.Session current(List<SessionStore.SessionEntry> catalog, String id) throws IOException {
        for (SessionStore.SessionEntry entry : catalog) if (entry.id.equals(id)) {
            if (!entry.readable()) throw new IOException("Unreadable session " + entry.id + ": " + entry.error);
            return entry.session();
        }
        throw new IOException("The current session is not in saved history");
    }

    /** Imported sessions carry one import time, not their records' span, so they are always read. */
    private static boolean overlaps(SessionStore.Session s, long end, long from, long until) {
        return "Imported".equals(s.version) || s.started < until && (end <= 0 || end >= from);
    }

    private static Totals totals(Cache cache, List<SessionStore.Session> sessions, Span span) throws IOException {
        int entered = 0, completed = 0;
        for (SessionStore.Session session : sessions) for (ActivityJournal.Visit visit : cache.runs(session)) {
            if (!span.keeps(visit.started) || !ParseDungeon.isDungeon(visit.map)) continue;   // runs count by entry time
            entered++;
            if (outcome(visit) == ActivityQueries.Outcome.COMPLETED) completed++;
        }
        Map<String, List<AppHistory.FameSample>> characters = new HashMap<>();
        Map<String, long[]> readings = new HashMap<>();   // per session: its first and last reading in the window
        for (SessionStore.Session session : sessions) for (AppHistory.FameSample sample : cache.fame(session)) {
            if (sample.time <= 0 || !span.keeps(sample.time)) continue;
            characters.computeIfAbsent(session.id + "/" + sample.character + "/" + sample.className, key -> new ArrayList<>()).add(sample);
            long[] reading = readings.computeIfAbsent(session.id, key -> new long[] {Long.MAX_VALUE, Long.MIN_VALUE});
            reading[0] = Math.min(reading[0], sample.time); reading[1] = Math.max(reading[1], sample.time);
        }
        Long gained = null; Double perHour = null; double[] series = new double[0];
        if (!characters.isEmpty()) {
            double[] buckets = new double[BUCKETS]; long gain = 0;
            for (List<AppHistory.FameSample> samples : characters.values()) {
                samples.sort(Comparator.comparingLong((AppHistory.FameSample s) -> s.time).thenComparingLong(s -> s.fame));
                for (int i = 1; i < samples.size(); i++) {   // a first reading is a baseline; decreases are ignored
                    long delta = samples.get(i).fame - samples.get(i - 1).fame;
                    if (delta > 0) { gain += delta; buckets[span.bucket(samples.get(i).time)] += delta; }
                }
            }
            gained = gain;
            // Hours with readings: each session's first-to-last span, so time between sessions (RealmShark closed) never counts.
            long reading = 0;
            for (long[] bounds : readings.values()) reading += bounds[1] - bounds[0];
            if (reading >= RATE_MINIMUM_MILLIS) perHour = gain * 3_600_000.0 / reading;
            series = new double[BUCKETS];
            for (int i = 0; i < BUCKETS; i++) series[i] = buckets[i] + (i == 0 ? 0 : series[i - 1]);
        }
        int untiered = 0, setTiered = 0, whites = 0, potions = 0;
        boolean lootRecorded = false;
        for (SessionStore.Session session : sessions) for (LootFacts.Bag bag : cache.loot(session)) {
            lootRecorded = true;   // loot was saved in this window's sessions, so a count of 0 is a real zero
            if (!span.keeps(bag.time())) continue;
            if (bag.white()) whites++;
            for (LootFacts.Item item : bag.items()) { if (item.untiered()) untiered++; if (item.setTiered()) setTiered++; if (item.potion()) potions++; }
        }
        return new Totals(span.window(), span.from(), span.until(), completed, entered, gained, perHour, series,
            untiered, setTiered, whites, potions, lootRecorded);
    }

    private static List<RecentRun> recent(Cache cache, List<SessionStore.Session> sessions, List<RecordedEncounter> recordings) throws IOException {
        List<Candidate> newest = new ArrayList<>();
        for (SessionStore.Session session : sessions) {
            // A session that ended before the fifth-newest run began cannot hold a newer one.
            long end = cache.end(session);
            if (newest.size() >= RECENT && end > 0 && !"Imported".equals(session.version) && end < newest.get(RECENT - 1).visit().started) continue;
            for (ActivityJournal.Visit visit : cache.runs(session))
                if (visit.id != null && !visit.id.isEmpty() && visit.started > 0 && ParseDungeon.isDungeon(visit.map))
                    newest.add(new Candidate(session, visit));
            newest.sort(NEWEST_FIRST);
            if (newest.size() > RECENT) newest.subList(RECENT, newest.size()).clear();
        }
        List<RecentRun> rows = new ArrayList<>();
        for (Candidate candidate : newest) {
            ActivityJournal.Visit visit = candidate.visit();
            VisitRef ref = new VisitRef(candidate.session().id, visit.id);
            rows.add(new RecentRun(ref, visit.map, outcome(visit).toString(), visit.started, visit.ended > 0 ? visit.ended : null,
                loot(cache.loot(candidate.session()), ref), dps(recordings, ref)));
        }
        return rows;
    }

    private static List<Integer> loot(List<LootFacts.Bag> bags, VisitRef ref) {
        List<LootFacts.Item> items = new ArrayList<>();
        for (LootFacts.Bag bag : bags) if (ref.equals(bag.visit())) items.addAll(bag.items());
        items.sort(Comparator.comparingInt(HomeArchive::notability));   // stable: drop order within a rank
        List<Integer> ids = new ArrayList<>();
        for (LootFacts.Item item : items) { if (ids.size() == LOOT_ICONS) break; ids.add(item.id()); }
        return ids;
    }
    static int notability(LootFacts.Item item) { return item.untiered() || item.setTiered() ? 0 : item.highTier() ? 1 : item.potion() ? 2 : 3; }

    /** Recorded local DPS from a recording linked to exactly this visit (the longest window if several), else null. */
    private static Double dps(List<RecordedEncounter> recordings, VisitRef ref) {
        RecordedEncounter best = null;
        for (RecordedEncounter recording : recordings) {
            if (recording == null || recording.link == null || !recording.link.linked() || !ref.equals(recording.link.visit)
                    || recording.localDamage == null || recording.windowSeconds == null || recording.windowSeconds <= 0) continue;
            if (best == null || recording.windowSeconds > best.windowSeconds) best = recording;
        }
        return best == null ? null : best.localDamage / best.windowSeconds;
    }

    private record Candidate(SessionStore.Session session, ActivityJournal.Visit visit) {}

    private record Span(Window window, long from, long until, long keepFrom, long keepUntil, long seriesEnd) {
        boolean keeps(long time) { return time >= keepFrom && time < keepUntil; }
        int bucket(long time) {
            long length = Math.max(1, seriesEnd - from);
            return (int) Math.max(0, Math.min(BUCKETS - 1, (time - from) * BUCKETS / length));
        }
    }

    /** One read per session and module per call, all over one catalog listing; recent runs reuse what the totals read. */
    private static final class Cache {
        private final SessionStore store;
        private final List<SessionStore.SessionEntry> catalog;
        private final Map<String, List<ActivityJournal.Visit>> runs = new HashMap<>();
        private final Map<String, List<LootFacts.Bag>> loot = new HashMap<>();
        private final Map<String, Long> ends = new HashMap<>();

        Cache(SessionStore store, List<SessionStore.SessionEntry> catalog) { this.store = store; this.catalog = catalog; }

        /** When the session ended; 0 while it is open. A crashed session (no end saved) ended when its files were last written. */
        long end(SessionStore.Session session) throws IOException {
            if (!crashed(session)) return session.ended;
            Long known = ends.get(session.id);
            if (known != null) return known;
            long last = session.started;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(store.directory().resolve(session.id))) {
                for (Path file : files) last = Math.max(last, Files.getLastModifiedTime(file).toMillis());
            }
            ends.put(session.id, last);
            return last;
        }
        /** An earlier launch that never saved its end (a crash); the current session is still open. */
        private boolean crashed(SessionStore.Session session) {
            return session.ended <= 0 && !session.id.equals(store.currentId()) && !"Imported".equals(session.version);
        }
        List<ActivityJournal.Visit> runs(SessionStore.Session session) throws IOException {
            List<ActivityJournal.Visit> visits = runs.get(session.id);
            if (visits == null) {
                List<ActivityJournal.Visit> read = new ArrayList<>();
                store.read(catalog, session.id, "runs", ActivityJournal.Visit.class, (s, visit) -> read.add(visit));
                // The store closes unfinished visits of sessions that saved their end; a crashed session's close the same way.
                if (crashed(session)) for (ActivityJournal.Visit visit : read)
                    if (visit.ended == 0) { visit.ended = Math.max(visit.started, visit.lastSeen); visit.endReason = "App ended"; }
                visits = read;
                runs.put(session.id, visits);
            }
            return visits;
        }
        List<LootFacts.Bag> loot(SessionStore.Session session) throws IOException {
            List<LootFacts.Bag> bags = loot.get(session.id);
            if (bags == null) { bags = new ArrayList<>(); LootFacts.read(store, catalog, session.id, bags::add); loot.put(session.id, bags); }
            return bags;
        }
        List<AppHistory.FameSample> fame(SessionStore.Session session) throws IOException {
            List<AppHistory.FameSample> samples = new ArrayList<>();
            store.read(catalog, session.id, "fame", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
            store.read(catalog, session.id, "fame-latest", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
            return samples;
        }
    }
}
