package tomato.gui.glance.home;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.dps.RecordedEncounter;
import tomato.gui.runs.RunOutcome;
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
     * {@code runsRecorded} is false when no visit (dungeon or not) was saved in the window's sessions: the run counts are then
     * unknown, not zero. {@code lootRecorded} is false when no loot bag was saved in the window's sessions: the loot counts
     * are then unknown, not zero. {@code unreadableSessions} counts saved sessions whose metadata could not be read and whose
     * files changed during the period, so they may hold records missing from these totals (Today only; This session reads only
     * the current session and is always 0).
     */
    public record Totals(Window window, long from, long until, int runsCompleted, int runsEntered, boolean runsRecorded,
                         Long fameGained, Double famePerHour, double[] fameSeries,
                         int untiered, int setTiered, int whiteBags, int potions, boolean lootRecorded, int unreadableSessions) {
        public Totals {
            Objects.requireNonNull(window, "window");
            fameSeries = fameSeries == null ? new double[0] : fameSeries.clone();
            if (unreadableSessions < 0) throw new IllegalArgumentException("unreadableSessions must not be negative");
        }
        /** Totals of a period whose saved sessions were all readable. */
        public Totals(Window window, long from, long until, int runsCompleted, int runsEntered, boolean runsRecorded, Long fameGained,
                      Double famePerHour, double[] fameSeries, int untiered, int setTiered, int whiteBags, int potions, boolean lootRecorded) {
            this(window, from, until, runsCompleted, runsEntered, runsRecorded, fameGained, famePerHour, fameSeries,
                untiered, setTiered, whiteBags, potions, lootRecorded, 0);
        }
        @Override public double[] fameSeries() { return fameSeries.clone(); }
        /** Content equality (the series by value), so an unchanged re-read is recognized as unchanged. */
        @Override public boolean equals(Object other) {
            return other instanceof Totals t && window == t.window && from == t.from && until == t.until && runsCompleted == t.runsCompleted
                && runsEntered == t.runsEntered && runsRecorded == t.runsRecorded && Objects.equals(fameGained, t.fameGained)
                && Objects.equals(famePerHour, t.famePerHour) && Arrays.equals(fameSeries, t.fameSeries) && untiered == t.untiered
                && setTiered == t.setTiered && whiteBags == t.whiteBags && potions == t.potions && lootRecorded == t.lootRecorded
                && unreadableSessions == t.unreadableSessions;
        }
        @Override public int hashCode() {
            return Objects.hash(window, from, until, runsCompleted, runsEntered, runsRecorded, fameGained, famePerHour,
                Arrays.hashCode(fameSeries), untiered, setTiered, whiteBags, potions, lootRecorded, unreadableSessions);
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
    /**
     * {@code unreadableRecent} counts saved sessions whose metadata could not be read and that may hold a run newer than the
     * oldest one listed; Recent runs says so instead of implying the list is complete.
     */
    public record Result(Totals totals, List<RecentRun> recent, int unreadableRecent) {
        public Result {
            Objects.requireNonNull(totals, "totals");
            recent = recent == null ? List.of() : List.copyOf(recent);
            if (unreadableRecent < 0) throw new IllegalArgumentException("unreadableRecent must not be negative");
        }
        /** A read whose saved sessions were all readable. */
        public Result(Totals totals, List<RecentRun> recent) { this(totals, recent, 0); }
    }

    static final int RECENT = 5, LOOT_ICONS = 8, BUCKETS = 12;
    /** Fame per hour needs at least ten minutes of readings (summed over sessions). */
    static final long RATE_MINIMUM_MILLIS = 10 * 60_000L;
    private static final Comparator<Candidate> NEWEST_FIRST = Comparator.comparingLong((Candidate c) -> c.visit().started).reversed()
        .thenComparing(c -> c.session().id).thenComparing(c -> c.visit().id);

    private HomeArchive() {}

    public static Result read(SessionStore store, Window window, long now, ZoneId zone, List<RecordedEncounter> recordings) throws IOException {
        return read(store, window, now, zone, recordings, new Cache());
    }

    /**
     * As above, keeping {@code kept}'s per-session facts between reads ({@link Cache}). Unreadable catalog entries are skipped,
     * as the archive queries skip them for all sessions. Today counts in {@link Totals#unreadableSessions()} those changed since
     * the day began; This session reads only the current session, so another session never fails it, while its own unreadable
     * metadata still does. Recent runs come from the readable sessions, and {@link Result#unreadableRecent()} counts the
     * unreadable ones that may hold a newer run.
     */
    public static Result read(SessionStore store, Window window, long now, ZoneId zone, List<RecordedEncounter> recordings,
                              Cache kept) throws IOException {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(window, "window"); Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(kept, "cache");
        List<SessionStore.SessionEntry> catalog = store.catalog();   // listed once; every module read below reuses it
        List<SessionStore.Session> sessions = new ArrayList<>();   // newest start first, as catalog() sorts them
        List<SessionStore.SessionEntry> unreadable = new ArrayList<>();
        for (SessionStore.SessionEntry entry : catalog) if (entry.readable()) sessions.add(entry.session()); else unreadable.add(entry);
        kept.prepare(store, catalog);
        Reader cache = new Reader(store, catalog, kept);   // this read's session facts
        Totals totals;
        if (window == Window.TODAY) {
            LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            long from = day.atStartOfDay(zone).toInstant().toEpochMilli(), until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            List<SessionStore.Session> inBounds = new ArrayList<>();
            for (SessionStore.Session session : sessions) if (overlaps(session, cache.end(session), from, until)) inBounds.add(session);
            totals = totals(cache, inBounds, new Span(window, from, until, from, until, Math.min(now, until)), changedSince(store, unreadable, from));
        } else {
            long from = store.started();   // every record of the current session counts; no time filter
            totals = totals(cache, List.of(current(catalog, store.currentId())),
                new Span(window, from, Math.max(from, now), Long.MIN_VALUE, Long.MAX_VALUE, now), 0);
        }
        List<RecentRun> recent = recent(cache, sessions, recordings == null ? List.of() : recordings);
        // An unreadable session can hold a newer run only if its files changed after the oldest listed run began.
        long since = recent.size() < RECENT ? Long.MIN_VALUE : recent.get(recent.size() - 1).started();
        return new Result(totals, recent, changedSince(store, unreadable, since));
    }

    /**
     * Home's wording of the shared outcome rule ({@link RunOutcome}), unchanged since P2: the Runs archive's labels, with a run
     * the app never closed (App ended) read as left, completion unconfirmed. RecentRunsCard shortens that to "Left".
     */
    static String label(RunOutcome outcome) {
        switch (outcome) {
            case COMPLETED: return ActivityQueries.Outcome.COMPLETED.toString();
            case IN_PROGRESS: return ActivityQueries.Outcome.IN_PROGRESS.toString();
            case UNKNOWN: return ActivityQueries.Outcome.UNKNOWN.toString();
            default: return ActivityQueries.Outcome.LEFT.toString();   // LEFT and APP_ENDED
        }
    }

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

    private static Totals totals(Reader cache, List<SessionStore.Session> sessions, Span span, int unreadable) throws IOException {
        int entered = 0, completed = 0;
        boolean runsRecorded = false;
        for (SessionStore.Session session : sessions) for (ActivityJournal.Visit visit : cache.runs(session)) {
            runsRecorded = true;   // visits were saved in this window's sessions, so a run count of 0 is a real zero
            if (!span.keeps(visit.started) || !ParseDungeon.isDungeon(visit.map)) continue;   // runs count by entry time
            entered++;
            if (cache.outcome(session, visit) == RunOutcome.COMPLETED) completed++;
        }
        Map<String, List<AppHistory.FameSample>> characters = new HashMap<>();
        Map<String, long[]> readings = new HashMap<>();   // per session: its first and last reading in the window
        for (SessionStore.Session session : sessions) for (AppHistory.FameSample sample : cache.fame(session)) {
            if (sample.time <= 0 || !span.keeps(sample.time)) continue;
            // One series per session, account, character id and class: two accounts' character #7 in one session never merge.
            // Legacy samples have no account (the empty part), so they group exactly as before.
            String series = session.id + "/" + (sample.account == null ? "" : sample.account) + "/" + sample.character + "/" + sample.className;
            characters.computeIfAbsent(series, key -> new ArrayList<>()).add(sample);
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
        return new Totals(span.window(), span.from(), span.until(), completed, entered, runsRecorded, gained, perHour, series,
            untiered, setTiered, whites, potions, lootRecorded, unreadable);
    }

    private static List<RecentRun> recent(Reader cache, List<SessionStore.Session> sessions, List<RecordedEncounter> recordings) throws IOException {
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
            rows.add(new RecentRun(ref, visit.map, label(cache.outcome(candidate.session(), visit)), visit.started,
                visit.ended > 0 ? visit.ended : null, loot(cache.loot(candidate.session()), ref), dps(recordings, ref)));
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

    /**
     * How many unreadable sessions have a file (in the session folder, or in its runs, loot, fame and fame-latest folders)
     * modified at or after {@code since}: only those can hold records from then on. A folder that cannot be listed counts,
     * because nothing rules it out; {@code since} Long.MIN_VALUE counts every unreadable session.
     */
    private static int changedSince(SessionStore store, List<SessionStore.SessionEntry> unreadable, long since) {
        int count = 0;
        for (SessionStore.SessionEntry entry : unreadable) {
            if (since == Long.MIN_VALUE) { count++; continue; }
            List<Stamp> stamp = new ArrayList<>();
            Path folder = store.directory().resolve(entry.id);
            try {
                Reader.list(stamp, folder, "");
                for (String module : Reader.FOLDERS) Reader.list(stamp, folder.resolve(module), module + "/");
            } catch (IOException unlisted) { count++; continue; }
            for (Stamp file : stamp) if (file.modified() >= since) { count++; break; }
        }
        return count;
    }

    /**
     * Per-session facts kept between reads: a crashed session's end, runs, loot bags and fame readings. One reader thread owns
     * it (Home's "home-archive"). A closed or imported session's facts are reused while its stamp is unchanged: the name, size
     * and modification time of every entry in the session folder and of the files in its runs, loot, fame and fame-latest
     * folders. The current session is read again every time and never kept.
     */
    public static final class Cache {
        private Path root;
        private final Map<String, Facts> sessions = new HashMap<>();

        public Cache() {}

        /** Sessions whose facts are kept (tests). */
        int size() { return sessions.size(); }

        /** Forgets everything when the store's folder changed, and the sessions that left the catalog. */
        private void prepare(SessionStore store, List<SessionStore.SessionEntry> catalog) {
            if (!store.directory().equals(root)) { sessions.clear(); root = store.directory(); }
            Set<String> listed = new HashSet<>();
            for (SessionStore.SessionEntry entry : catalog) listed.add(entry.id);
            sessions.keySet().retainAll(listed);
        }
    }

    /** One session's facts, each read on first use; {@code stamp} is null for the current session. */
    private static final class Facts {
        final List<Stamp> stamp;
        Long end;
        List<ActivityJournal.Visit> runs;
        List<LootFacts.Bag> loot;
        List<AppHistory.FameSample> fame;
        Facts(List<Stamp> stamp) { this.stamp = stamp; }
    }

    /** One entry as last seen: its path inside the session folder, size and modification time (epoch ms). */
    private record Stamp(String name, long size, long modified) {}

    /** One read over one catalog listing: each session is stamped at most once; its facts come from the Cache while unchanged. */
    private static final class Reader {
        private static final String[] FOLDERS = {"runs", "loot", "fame", "fame-latest"};
        private final SessionStore store;
        private final List<SessionStore.SessionEntry> catalog;
        private final Cache kept;
        private final Map<String, Facts> checked = new HashMap<>();

        Reader(SessionStore store, List<SessionStore.SessionEntry> catalog, Cache kept) { this.store = store; this.catalog = catalog; this.kept = kept; }

        private Facts facts(SessionStore.Session session) throws IOException {
            Facts facts = checked.get(session.id);
            if (facts != null) return facts;
            if (session.id.equals(store.currentId())) facts = new Facts(null);   // still being written: never kept
            else {
                List<Stamp> stamp = new ArrayList<>();
                Path folder = store.directory().resolve(session.id);
                list(stamp, folder, "");
                for (String module : FOLDERS) list(stamp, folder.resolve(module), module + "/");
                stamp.sort(Comparator.comparing(Stamp::name));
                facts = kept.sessions.get(session.id);
                if (facts == null || !facts.stamp.equals(stamp)) { facts = new Facts(stamp); kept.sessions.put(session.id, facts); }
            }
            checked.put(session.id, facts);
            return facts;
        }

        private static void list(List<Stamp> stamp, Path folder, String prefix) throws IOException {
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                for (Path file : files) {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    stamp.add(new Stamp(prefix + file.getFileName(), attributes.size(), attributes.lastModifiedTime().toMillis()));
                }
            }
        }

        /** When the session ended; 0 while it is open. A crashed session (no end saved) ended when its files were last written. */
        long end(SessionStore.Session session) throws IOException {
            if (!crashed(session)) return session.ended;
            Facts facts = facts(session);
            if (facts.end == null) {
                long last = session.started;   // the folder's own entries, as before the cache
                for (Stamp file : facts.stamp) if (file.name().indexOf('/') < 0) last = Math.max(last, file.modified());
                facts.end = last;
            }
            return facts.end;
        }
        /** An earlier launch that never saved its end (a crash); the current session is still open. */
        private boolean crashed(SessionStore.Session session) {
            return session.ended <= 0 && !session.id.equals(store.currentId()) && !"Imported".equals(session.version);
        }
        /**
         * The shared outcome rule for a visit of {@code session} ({@link #runs} already closed a crashed session's unfinished visits
         * with RunOutcome's App ended marker). Home's sessions still open are the current one and, as before, an import that saved
         * no end (imports save one, so this only keeps Home's reading of such a folder).
         */
        RunOutcome outcome(SessionStore.Session session, ActivityJournal.Visit visit) {
            return RunOutcome.of(visit, session.ended > 0, session.ended <= 0 && !crashed(session));
        }
        List<ActivityJournal.Visit> runs(SessionStore.Session session) throws IOException {
            Facts facts = facts(session);
            if (facts.runs == null) {
                List<ActivityJournal.Visit> read = new ArrayList<>();
                store.read(catalog, session.id, "runs", ActivityJournal.Visit.class, (s, visit) -> read.add(visit));
                // The store closes unfinished visits of sessions that saved their end; a crashed session's close the same way.
                if (crashed(session)) for (ActivityJournal.Visit visit : read)
                    if (visit.ended == 0) { visit.ended = Math.max(visit.started, visit.lastSeen); visit.endReason = RunOutcome.APP_ENDED_REASON; }
                facts.runs = read;
            }
            return facts.runs;
        }
        List<LootFacts.Bag> loot(SessionStore.Session session) throws IOException {
            Facts facts = facts(session);
            if (facts.loot == null) { List<LootFacts.Bag> bags = new ArrayList<>(); LootFacts.read(store, catalog, session.id, bags::add); facts.loot = bags; }
            return facts.loot;
        }
        List<AppHistory.FameSample> fame(SessionStore.Session session) throws IOException {
            Facts facts = facts(session);
            if (facts.fame == null) {
                List<AppHistory.FameSample> samples = new ArrayList<>();
                store.read(catalog, session.id, "fame", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
                store.read(catalog, session.id, "fame-latest", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
                facts.fame = samples;
            }
            return facts.fame;
        }
    }
}
