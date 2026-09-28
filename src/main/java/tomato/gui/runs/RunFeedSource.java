package tomato.gui.runs;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.stats.LootFacts;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseDungeon;

/**
 * The run feed's reader of saved history (spec §6.3 Feed, §8.5). Off the EDT only; the EDT applies the immutable
 * {@link RunFeedModel} of a {@link Page}.
 *
 * <p>Runs come from the Runs archive: {@link #first} pins the saved {@code runs} of every session and opens a disk-backed
 * {@link ArchiveResult} with the archive's own projection, facets, text search and newest-first order
 * ({@link RunFeedQuery#archiveQuery()}), and this source adds the shared outcome rule ({@link RunOutcome}, read with the
 * pinned session's end) and the observed party to each projected run. Only dungeon runs with a visit ID and an entry time
 * become cards (other matches are counted in {@link Page#unplaced()}; the Table view lists them). Pages hold
 * {@value #PAGE} runs; {@link #more} reads the next ones from the same pinned result, so runs saved meanwhile never shift
 * or repeat a page. The current run appears once its checkpoint is saved (about every 10 s).
 *
 * <p>Card facts are read only for the sessions of the loaded runs and joined only by exact {@link VisitRef}: loot bags
 * ({@link LootFacts}), fame readings ({@code fame} and {@code fame-latest}, {@link FameGains} over the session's runs
 * coverage) and combat records ({@link CombatFacts}). A closed session's facts are kept while its stamp is unchanged (the
 * name, size and modification time of every entry of its folder and of its loot, fame, fame-latest and encounters folders,
 * as {@code HomeArchive.Cache} does); the current session is read again every time. Sessions whose metadata cannot be read
 * are left out of the pin, and a damaged {@code runs} file fails the read, as the archive does. One session's facts degrade
 * alone: when its loot, fame or combat records cannot be read, its runs' cards show that fact as unknown with a reason
 * ({@link RunCardModel#LOOT_UNREADABLE}, null fame, {@link RunCardModel#COMBAT_UNREADABLE}); a single damaged combat record
 * is skipped ({@link CombatFacts#read}). Both are named in {@link Page#issues()}, so the feed can say it is partial.
 */
public final class RunFeedSource {
    /** Runs per page. */
    public static final int PAGE = 50;
    /** The session folders whose files the kept facts come from (besides the session folder's own entries). */
    private static final String[] FOLDERS = {"loot", "fame", "fame-latest", CombatFacts.RECORDS};

    private final SessionStore store;
    private final ZoneId zone;
    private final LongSupplier clock;
    private final Path scratch;
    /** Closed sessions' facts by session ID, for {@link #keptRoot}; guarded by this. */
    private final Map<String, Facts> kept = new HashMap<>();
    private Path keptRoot;
    /** Reads one session's combat records ({@link CombatFacts#read}); tests replace it to fail one session's read. */
    private volatile CombatReader records = CombatFacts::read;

    /** {@link CombatFacts#read}'s shape. */
    @FunctionalInterface interface CombatReader {
        void read(SessionStore store, List<SessionStore.SessionEntry> catalog, String scope, java.util.function.Consumer<CombatRecord> sink) throws IOException;
    }

    /** Pins go to the system temporary folder, as the archive workspaces' do. */
    public RunFeedSource(SessionStore store, ZoneId zone, LongSupplier clock) {
        this(store, zone, clock, Paths.get(System.getProperty("java.io.tmpdir"), "realmshark-run-feed"));
    }

    /** As above with the scratch folder for pinned results (tests). */
    public RunFeedSource(SessionStore store, ZoneId zone, LongSupplier clock, Path scratch) {
        this.store = Objects.requireNonNull(store, "store"); this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock"); this.scratch = Objects.requireNonNull(scratch, "scratch");
    }

    /** The newest {@value #PAGE} runs matching {@code query}. Close the page (or the last page {@link #more} made from it) when done. */
    public Page first(RunFeedQuery query, Cancellation cancel) throws IOException {
        offEdt();
        Objects.requireNonNull(query, "query"); Objects.requireNonNull(cancel, "cancel");
        Adapter adapter = new Adapter(query);
        ArchiveResult<Projected> result = ArchiveResult.open(store, query.archiveQuery(), adapter, scratch, cancel);
        Shared shared;
        try {
            List<String> issues = new ArrayList<>();
            for (JsonElement issue : result.manifest().getAsJsonArray("issues")) issues.add(issue.getAsString());
            shared = new Shared(result.lease(), issues);
        } finally { result.close(); }   // the lease keeps the pinned files until the last page using them is closed
        try { return read(shared, query, List.of(), shared.issues, result.matches, adapter.unplaced, cancel); }
        catch (IOException | RuntimeException | Error failure) { shared.release(); throw failure; }
    }

    /**
     * {@code previous}'s runs and the next {@value #PAGE} of the same pinned result; when none remain, the same runs. The new
     * page shares the pinned result: closing {@code previous} afterwards does not affect it.
     *
     * @throws IllegalStateException when {@code previous} was closed
     */
    public Page more(Page previous, Cancellation cancel) throws IOException {
        offEdt();
        Objects.requireNonNull(previous, "previous"); Objects.requireNonNull(cancel, "cancel");
        if (previous.closed.get()) throw new IllegalStateException("This feed page was closed");
        Shared shared = previous.shared.retain();
        try {
            if (!previous.model.more())
                return new Page(previous.model, previous.query, previous.matches, previous.unplaced, previous.loaded, previous.issues, shared);
            return read(shared, previous.query, previous.model.cards(), previous.issues, previous.matches, previous.unplaced, cancel);
        } catch (IOException | RuntimeException | Error failure) { shared.release(); throw failure; }
    }

    /** Closed sessions whose facts are kept (tests). */
    synchronized int cachedSessions() { return kept.size(); }

    /** Replaces the combat record reader (tests: a session whose combat read fails). */
    void combatReader(CombatReader reader) { records = Objects.requireNonNull(reader, "reader"); }

    private static void offEdt() { if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read saved runs off the EDT"); }

    /**
     * The next page after {@code loaded}: its rows, the first unloaded run's day, and the facts of the new rows' sessions.
     * {@code known} are the issues so far (the pin's, then earlier pages'); the new sessions' unreadable facts are added.
     */
    private Page read(Shared shared, RunFeedQuery query, List<RunCardModel> loaded, List<String> known, long matches, long unplaced,
                      Cancellation cancel) throws IOException {
        long from = loaded.size();   // a whole number of pages: only the last page is short, and more() stops there
        List<ArchiveRow<Projected>> rows = new ArrayList<>(PAGE);
        if (from < matches) shared.lease.stream(ExportSelection.page(from / PAGE, PAGE), rows::add, cancel);
        long next = from + rows.size();
        LocalDate continuesOn = null;
        if (next < matches) {   // "more below" only when the first unloaded run was entered on the last loaded day
            List<ArchiveRow<Projected>> peek = new ArrayList<>(1);
            shared.lease.stream(ExportSelection.page(next, 1), peek::add, cancel);
            continuesOn = RunFeedModel.day(peek.get(0).value.run.time, zone);
        }
        List<SessionStore.SessionEntry> catalog = store.catalog(cancel);   // listed once for every session read below
        forgetGone(catalog);
        Map<String, Facts> facts = new HashMap<>();
        Set<String> issues = new LinkedHashSet<>(known);
        List<RunCardModel> cards = new ArrayList<>(loaded);
        for (ArchiveRow<Projected> row : rows) {
            cancel.check();
            Facts session = facts.get(row.ref.session);
            if (session == null) {
                facts.put(row.ref.session, session = facts(catalog, row.ref.session));
                issues.addAll(session.issues());
            }
            cards.add(card(row, session));
        }
        cancel.check();
        return new Page(RunFeedModel.of(cards, next < matches, continuesOn, zone, clock.getAsLong()), query, matches, unplaced, next,
            List.copyOf(issues), shared);
    }

    /** A card from the pinned row and its session's facts; a fact the session could not read is passed on as null (unknown). */
    private static RunCardModel card(ArchiveRow<Projected> row, Facts facts) {
        ActivityQueries.Row run = row.value.run;
        VisitRef ref = new VisitRef(row.ref.session, run.visitId);
        return RunCardModel.of(ref, run.map, row.value.outcome, run.time, run.durationMillis, row.value.rosterSize, run.progress,
            facts.combat() == null ? null : facts.combat().getOrDefault(ref, List.of()),
            facts.loot() == null ? null : facts.loot().getOrDefault(ref, List.of()),
            facts.fame() == null ? null : FameGains.of(facts.fame(), ref).orElse(null));
    }

    /** Forgets everything when the store's folder changed, and the sessions that left the catalog. */
    private synchronized void forgetGone(List<SessionStore.SessionEntry> catalog) {
        if (!store.directory().equals(keptRoot)) { kept.clear(); keptRoot = store.directory(); }
        Set<String> listed = new HashSet<>();
        for (SessionStore.SessionEntry entry : catalog) listed.add(entry.id);
        kept.keySet().retainAll(listed);
    }

    /** One session's facts: kept while a closed session's stamp is unchanged; the current session's are read every time. */
    private Facts facts(List<SessionStore.SessionEntry> catalog, String session) throws IOException {
        SessionStore.SessionEntry entry = null;
        for (SessionStore.SessionEntry listed : catalog) if (listed.id.equals(session)) entry = listed;
        // The pin read this session a moment ago: it was deleted or damaged since. Its runs' facts are unknown, not empty.
        if (entry == null || !entry.readable()) throw new IOException("Saved session " + session + " changed during the read; refresh to read it again");
        if (session.equals(store.currentId())) return read(catalog, entry, null);   // still being written: never kept
        List<Stamp> stamp = stamp(store.directory().resolve(session));
        synchronized (this) {
            Facts known = kept.get(session);
            if (known != null && known.stamp.equals(stamp)) return known;
        }
        Facts read = read(catalog, entry, stamp);
        synchronized (this) { kept.put(session, read); }
        return read;
    }

    /**
     * One session's loot, fame and combat facts, each read on its own: a module that cannot be read (a damaged journal line
     * or checkpoint, an unlistable folder) is null (unknown) and named in {@link Facts#issues}, and the others still show.
     */
    private Facts read(List<SessionStore.SessionEntry> catalog, SessionStore.SessionEntry entry, List<Stamp> stamp) {
        List<String> issues = new ArrayList<>();
        Map<VisitRef, List<LootFacts.Bag>> bags = new HashMap<>(), loot = bags;
        try { LootFacts.read(store, catalog, entry.id, bag -> { if (bag.visit() != null) bags.computeIfAbsent(bag.visit(), v -> new ArrayList<>()).add(bag); }); }
        catch (IOException | RuntimeException failure) { unreadable(failure, entry.id, "loot", issues); loot = null; }
        Map<VisitRef, Long> fame;
        try {
            List<AppHistory.FameSample> samples = new ArrayList<>();
            store.read(catalog, entry.id, "fame", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
            store.read(catalog, entry.id, "fame-latest", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
            fame = FameGains.byVisit(samples, entry.id, entry.availability("runs"));
        } catch (IOException | RuntimeException failure) { unreadable(failure, entry.id, "fame", issues); fame = null; }
        Map<VisitRef, List<CombatRecord>> combat;
        try {
            List<CombatRecord> saved = new ArrayList<>();
            records.read(store, catalog, entry.id, saved::add);
            combat = CombatFacts.byVisit(saved);
        } catch (IOException | RuntimeException failure) { unreadable(failure, entry.id, CombatFacts.RECORDS, issues); combat = null; }
        return new Facts(stamp, loot, fame, combat, List.copyOf(issues));
    }

    /** Notes a module of {@code session} that could not be read, by the failure's kind (never its message, which may hold a path). */
    private static void unreadable(Exception failure, String session, String module, List<String> issues) {
        if (failure instanceof java.util.concurrent.CancellationException) throw (java.util.concurrent.CancellationException) failure;
        issues.add(session + ": " + module + " could not be read (" + failure.getClass().getSimpleName() + ")");
    }

    /** The session folder's entries and the files of {@link #FOLDERS}, by name. */
    private static List<Stamp> stamp(Path folder) throws IOException {
        List<Stamp> stamp = new ArrayList<>();
        list(stamp, folder, "");
        for (String module : FOLDERS) list(stamp, folder.resolve(module), module + "/");
        stamp.sort(Comparator.comparing(Stamp::name));
        return stamp;
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

    /**
     * Loaded runs as a {@link RunFeedModel}, holding the pinned result {@link #more} reads from. Close it when it is replaced
     * or no longer shown: the pinned files are removed when the last page using them is closed. Closing twice does nothing.
     */
    public static final class Page implements AutoCloseable {
        private final RunFeedModel model;
        private final RunFeedQuery query;
        private final long matches, unplaced, loaded;
        private final List<String> issues;
        private final Shared shared;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Page(RunFeedModel model, RunFeedQuery query, long matches, long unplaced, long loaded, List<String> issues, Shared shared) {
            this.model = model; this.query = query; this.matches = matches; this.unplaced = unplaced; this.loaded = loaded;
            this.issues = issues; this.shared = shared;
        }

        public RunFeedModel model() { return model; }
        public RunFeedQuery query() { return query; }
        /** Saved runs matching the query in the pinned result (loaded or not). */
        public long matches() { return matches; }
        /** Saved dungeon runs matching the query without a visit ID or entry time: no card can show them. */
        public long unplaced() { return unplaced; }
        /**
         * Why the loaded runs may be partial: the pin's issues in the archive's words (saved sessions whose metadata could not
         * be read, left out; unfinished journal tails), then each loaded session's module that could not be read
         * ("&lt;session&gt;: loot could not be read (IOException)"; also fame and encounters). Empty when everything was read.
         */
        public List<String> issues() { return issues; }
        @Override public void close() { if (closed.compareAndSet(false, true)) shared.release(); }
    }

    /** The pinned result's lease, shared by the pages of one query; released with the last of them. */
    private static final class Shared {
        final ArchiveResult.Lease<Projected> lease;
        final List<String> issues;
        private int users = 1;

        Shared(ArchiveResult.Lease<Projected> lease, List<String> issues) { this.lease = lease; this.issues = List.copyOf(issues); }

        synchronized Shared retain() {
            if (users == 0) throw new IllegalStateException("The feed's pinned result was released");
            users++;
            return this;
        }

        void release() {
            boolean last;
            synchronized (this) { last = users > 0 && --users == 0; }
            if (last) lease.close();   // off the EDT the files go now; on the EDT the archive's cleanup worker removes them
        }
    }

    /**
     * One session's facts as last read ({@code stamp} null for the current session), by exact visit. A module that could not
     * be read is null (its facts unknown) and named in {@code issues}; kept with the stamp like the rest, since reading the
     * same files again fails the same way.
     */
    private record Facts(List<Stamp> stamp, Map<VisitRef, List<LootFacts.Bag>> loot, Map<VisitRef, Long> fame,
                         Map<VisitRef, List<CombatRecord>> combat, List<String> issues) {}

    /** One entry as last seen: its path inside the session folder, size and modification time (epoch ms). */
    private record Stamp(String name, long size, long modified) {}

    /**
     * A saved dungeon run as the Runs archive projects it ({@link ActivityQueries#visit}), with the shared outcome and the
     * observed party. A plain Gson class: the archive result stores its rows on disk.
     */
    static final class Projected {
        ActivityQueries.Row run;
        RunOutcome outcome;
        Integer rosterSize;

        Projected() {}
    }

    /**
     * The Runs archive's adapter for the feed: the same projection, facets, text search and order (delegated), pinning only
     * {@code runs}, plus the feed's exact outcome and dungeon filter and its identity requirement.
     */
    private static final class Adapter implements ArchiveAdapter<Projected, ActivityQueries.Filters, ActivityQueries.Sort> {
        private final ArchiveAdapter<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs = ActivityQueries.adapter(ActivityPanel.Mode.RUNS);
        private final RunFeedQuery query;
        /** Matching runs left out for lack of a visit ID or entry time (one result's scan owns it). */
        long unplaced;

        Adapter(RunFeedQuery query) { this.query = query; }

        @Override public Class<Projected> rowType() { return Projected.class; }
        @Override public String unit() { return "runs"; }
        @Override public List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> q) {
            // The Table view also pins the timeline for its linked exports; cards never read it.
            return List.of(new ReadSnapshot.Source(q.resolvedScope(store), "runs"));
        }
        @Override public void validate(ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> q) { runs.validate(q); }
        @Override public void scan(ReadSnapshot pin, ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> q, Sink<Projected> sink, Cancellation cancel) throws IOException {
            String current = pin.resolveScope(ArchiveQuery.CURRENT);
            Map<String, boolean[]> sessions = new HashMap<>();
            pin.read("runs", ActivityJournal.Visit.class, source -> {
                ActivityJournal.Visit visit = source.value;
                if (!ParseDungeon.isDungeon(visit.map)) return;   // the Runs archive's rows: dungeon visits
                boolean[] state = sessions.computeIfAbsent(source.ref.session, id -> state(pin.session(id), id.equals(current)));
                Projected projected = new Projected();
                projected.run = ActivityQueries.visit(visit);
                projected.outcome = RunOutcome.of(visit, state[0], state[1]);
                projected.rosterSize = visit.rosterSize;
                sink.accept(new ArchiveRow<>(source.ref, projected));
            }, cancel);
        }
        /**
         * {ended, current} for {@link RunOutcome#of}, as Home reads its sessions: a session is still open while it is this app
         * run's (or an import that saved no end); one that neither saved its end nor is open ended with the app (a crash). The
         * pin already closed the unfinished runs of sessions that saved their end with the App ended marker.
         */
        private static boolean[] state(SessionStore.Session session, boolean current) {
            if (session == null) return new boolean[] {false, false};
            return new boolean[] {session.ended > 0, session.ended <= 0 && (current || "Imported".equals(session.version))};
        }
        @Override public boolean matches(ArchiveRow<Projected> row, ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> q) {
            Projected projected = row.value;
            if (!runs.matches(new ArchiveRow<>(row.ref, projected.run), q) || !query.matches(projected.outcome, projected.run.map)) return false;
            if (projected.run.time == null || projected.run.visitId == null || projected.run.visitId.isEmpty()) { unplaced++; return false; }
            return true;
        }
        @Override public Long time(ArchiveRow<Projected> row) { return row.value.run.time; }
        @Override public Long endTime(ArchiveRow<Projected> row) { return row.value.run.end; }
        @Override public Comparator<Projected> comparator(ActivityQueries.Sort field) {
            Comparator<ActivityQueries.Row> order = runs.comparator(field);
            return order == null ? null : Comparator.comparing(projected -> projected.run, order);
        }
        @Override public Map<String, String> dependencies() {
            Map<String, String> dependencies = new TreeMap<>(runs.dependencies());
            dependencies.put("runFeed", "1; RunOutcome with the pinned session's end; dungeon runs with a visit ID and entry time; party = rosterSize");
            return dependencies;
        }
    }
}
