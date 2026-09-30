package tomato.gui.runs;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.stats.LootFacts;
import tomato.history.AppHistory;
import tomato.history.SessionStamps;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveAdapter;
import tomato.history.archive.ArchiveQuery;
import tomato.history.archive.ArchiveRow;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseDungeon;

/**
 * The run feed's reader of saved history (spec §6.3 Feed, §8.5). Off the EDT only; the EDT applies the immutable
 * {@link RunFeedModel} of a {@link Page}.
 *
 * <p>Each session's dungeon runs are projected directly from the store and kept by {@link SessionStamps} over its
 * {@code runs} folder; the current session is read every time. The Runs archive's predicates and ordering are applied
 * before paging. {@link #first} builds one immutable sorted list shared by its pages; {@link #more} slices that same list,
 * so later saves never shift or repeat a page. No archive capture, hashes, scratch files or disk sort are needed. Only
 * runs with a visit ID and entry time become cards; other matches are counted in {@link Page#unplaced()}.
 *
 * <p>Card facts are read only for the sessions of the loaded runs and joined only by exact {@link VisitRef}: loot bags
 * ({@link LootFacts}), fame readings ({@code fame} and {@code fame-latest}, {@link FameGains} over the session's runs
 * coverage) and combat records ({@link CombatFacts}). A closed session's facts are kept while its stamp is unchanged (the
 * name, size and modification time of every entry of its folder and of its loot, fame, fame-latest and encounters folders;
 * {@link SessionStamps}, as Home's archive and the Dungeons cards keep theirs); the current session is read again every
 * time. Sessions whose metadata cannot be read are left out of the result, and a damaged {@code runs} file fails the read, as
 * the archive does. One session's facts degrade alone: when its loot, fame or combat records cannot be read, its runs' cards
 * show that fact as unknown with a reason ({@link RunCardModel#LOOT_UNREADABLE}, null fame,
 * {@link RunCardModel#COMBAT_UNREADABLE}); a single damaged combat record is skipped ({@link CombatFacts#read}). Both are
 * named in {@link Page#issues()}, so the feed can say it is partial.
 */
public final class RunFeedSource {
    /** Runs per page. */
    public static final int PAGE = 50;
    /** The archive's largest record: a bigger checkpoint or journal line fails the read, as the archive pin's did. */
    private static final int MAX_RECORD = 16 * 1024 * 1024;
    /** The session folders whose files the kept facts come from (besides the session folder's own entries). */
    private static final String[] FOLDERS = {"loot", "fame", "fame-latest", CombatFacts.RECORDS};

    private final SessionStore store;
    private final ZoneId zone;
    private final LongSupplier clock;
    /** Closed sessions' projected runs, independent of the loaded cards' facts. */
    private final SessionStamps<Runs> runs = new SessionStamps<>("runs");
    private final AtomicInteger reads = new AtomicInteger();
    /** Closed sessions' facts, kept by their stamps. */
    private final SessionStamps<Facts> kept = new SessionStamps<>(FOLDERS);
    /** Reads one session's combat records ({@link CombatFacts#read}); tests replace it to fail one session's read. */
    private volatile SessionFacts.CombatReader records = CombatFacts::read;

    /** Reads saved runs directly, without temporary files. */
    public RunFeedSource(SessionStore store, ZoneId zone, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store"); this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The newest {@value #PAGE} runs matching {@code query}. Close the page (or the last page {@link #more} made from it) when done. */
    public Page first(RunFeedQuery query, Cancellation cancel) throws IOException {
        offEdt();
        Objects.requireNonNull(query, "query"); Objects.requireNonNull(cancel, "cancel");
        cancel.check();
        ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> q = query.archiveQuery();
        ArchiveAdapter<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> adapter = ActivityQueries.adapter(ActivityPanel.Mode.RUNS);
        adapter.validate(q);
        List<SessionStore.SessionEntry> catalog = store.catalog(cancel);
        runs.forgetGone(store, catalog);
        List<ArchiveRow<Projected>> matching = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        long unplaced = 0;
        for (SessionStore.SessionEntry entry : catalog) {
            cancel.check();
            if (!entry.readable()) { issues.add(entry.id + ": " + entry.error); continue; }
            Runs saved = runs.get(store, entry.id, stamp -> runs(entry, cancel));
            issues.addAll(saved.issues);
            for (ArchiveRow<Projected> row : saved.rows) {
                cancel.check();
                Projected projected = row.value;
                ArchiveRow<ActivityQueries.Row> run = row.project(projected.run);
                if (!adapter.inBounds(run, q) || !adapter.matches(run, q) || !query.matches(projected.outcome, projected.run.map)) continue;
                if (projected.run.time == null || projected.run.visitId == null || projected.run.visitId.isEmpty()) { unplaced++; continue; }
                matching.add(row);
            }
        }
        Comparator<ArchiveRow<Projected>> order = (a, b) -> Boolean.compare(adapter.sortsLast(a.value.run), adapter.sortsLast(b.value.run));
        for (ArchiveQuery.Order<ActivityQueries.Sort> item : q.order()) {
            Comparator<ActivityQueries.Row> values = Objects.requireNonNull(adapter.comparator(item.field), "Unsupported sort field");
            Comparator<ActivityQueries.Row> field = item.direction == ArchiveQuery.Direction.DESCENDING ? values.reversed() : values;
            order = order.thenComparing((a, b) -> field.compare(a.value.run, b.value.run));
        }
        Comparator<ArchiveRow<Projected>> sorted = order.thenComparing(row -> row.ref);
        matching.sort((a, b) -> { cancel.check(); return sorted.compare(a, b); });
        cancel.check();
        Shared shared = new Shared(matching, issues);
        return read(shared, query, List.of(), shared.issues, matching.size(), unplaced, cancel);
    }

    /**
     * {@code previous}'s runs and the next {@value #PAGE} of the same saved result; when none remain, the same runs. The new
     * page shares the saved result: closing {@code previous} afterwards does not affect it.
     *
     * @throws IllegalStateException when {@code previous} was closed
     */
    public Page more(Page previous, Cancellation cancel) throws IOException {
        offEdt();
        Objects.requireNonNull(previous, "previous"); Objects.requireNonNull(cancel, "cancel");
        if (previous.closed.get()) throw new IllegalStateException("This feed page was closed");
        cancel.check();
        Shared shared = previous.shared;
        if (!previous.model.more())
            return new Page(previous.model, previous.query, previous.matches, previous.unplaced, previous.loaded, previous.issues, shared);
        return read(shared, previous.query, previous.model.cards(), previous.issues, previous.matches, previous.unplaced, cancel);
    }

    /** Closed sessions whose facts are kept (tests). */
    int cachedSessions() { return kept.size(); }

    /** Sessions whose runs were read from disk, including the current session each time (tests). */
    int sessionReads() { return reads.get(); }

    /** Replaces the combat record reader (tests: a session whose combat read fails). */
    void combatReader(SessionFacts.CombatReader reader) { records = Objects.requireNonNull(reader, "reader"); }

    private static void offEdt() { if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read saved runs off the EDT"); }

    /**
     * The next page after {@code loaded}: its rows, the first unloaded run's day, and the facts of the new rows' sessions.
     * {@code known} are the issues so far (the runs', then earlier pages'); the new sessions' unreadable facts are added.
     */
    private Page read(Shared shared, RunFeedQuery query, List<RunCardModel> loaded, List<String> known, long matches, long unplaced,
                      Cancellation cancel) throws IOException {
        cancel.check();
        int from = loaded.size(), next = Math.min(from + PAGE, shared.rows.size());
        List<ArchiveRow<Projected>> rows = shared.rows.subList(from, next);
        LocalDate continuesOn = null;
        if (next < matches) {   // "more below" only when the first unloaded run was entered on the last loaded day
            continuesOn = RunFeedModel.day(shared.rows.get(next).value.run.time, zone);
        }
        List<SessionStore.SessionEntry> catalog = store.catalog(cancel);   // listed once for every session read below
        kept.forgetGone(store, catalog);   // everything when the store's folder changed, and the sessions that left the catalog
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

    /** A card from the saved row and its session's facts; a fact the session could not read is passed on as null (unknown). */
    private static RunCardModel card(ArchiveRow<Projected> row, Facts facts) {
        ActivityQueries.Row run = row.value.run;
        VisitRef ref = new VisitRef(row.ref.session, run.visitId);
        return RunCardModel.of(ref, run.map, row.value.outcome, run.time, run.durationMillis, row.value.rosterSize, run.progress,
            facts.combat() == null ? null : facts.combat().getOrDefault(ref, List.of()),
            facts.loot(),   // every saved bag: none at all is loot unknown, not a known none
            facts.fame() == null ? null : FameGains.of(facts.fame(), ref).orElse(null));
    }

    /** One session's facts: kept while a closed session's stamp is unchanged; the current session's are read every time. */
    private Facts facts(List<SessionStore.SessionEntry> catalog, String session) throws IOException {
        SessionStore.SessionEntry entry = null;
        for (SessionStore.SessionEntry listed : catalog) if (listed.id.equals(session)) entry = listed;
        // The projection read this session a moment ago: it was deleted or damaged since. Its runs' facts are unknown, not empty.
        if (entry == null || !entry.readable()) throw new IOException("Saved session " + session + " changed during the read; refresh to read it again");
        SessionStore.SessionEntry listed = entry;
        return kept.get(store, session, stamp -> read(catalog, listed, stamp));   // read() itself throws no IOException
    }

    /**
     * One session's loot, fame and combat facts, each read on its own: a module that cannot be read (a damaged journal line
     * or checkpoint, an unlistable folder) is null (unknown) and named in {@link Facts#issues}, and the others still show.
     */
    private Facts read(List<SessionStore.SessionEntry> catalog, SessionStore.SessionEntry entry, List<SessionStamps.Stamp> stamp) {
        List<String> issues = new ArrayList<>();
        List<LootFacts.Bag> loot = SessionFacts.loot(store, catalog, entry.id, issues);
        Map<VisitRef, Long> fame;
        try {
            List<AppHistory.FameSample> samples = new ArrayList<>();
            store.read(catalog, entry.id, "fame", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
            store.read(catalog, entry.id, "fame-latest", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
            fame = FameGains.byVisit(samples, entry.id, entry.availability("runs"));
        } catch (IOException | RuntimeException failure) { SessionFacts.unreadable(failure, entry.id, "fame", issues); fame = null; }
        Map<VisitRef, List<CombatRecord>> combat = SessionFacts.combat(records, store, catalog, entry.id, issues);
        return new Facts(stamp, loot, fame, combat, List.copyOf(issues));
    }

    /**
     * Loaded runs as a {@link RunFeedModel}, holding the immutable result {@link #more} reads from. Close it when it is replaced
     * or no longer shown: no disk resources are held. Closing twice does nothing.
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
        /** Saved runs matching the query in the immutable result (loaded or not). */
        public long matches() { return matches; }
        /** Saved dungeon runs matching the query without a visit ID or entry time: no card can show them. */
        public long unplaced() { return unplaced; }
        /**
         * Why the loaded runs may be partial: the saved runs' issues in the archive's words (saved sessions whose metadata could not
         * be read, left out; unfinished journal tails), then each loaded session's module that could not be read
         * ("&lt;session&gt;: loot could not be read (IOException)"; also fame and encounters). Empty when everything was read.
         */
        public List<String> issues() { return issues; }
        @Override public void close() { closed.set(true); }
    }

    /** The immutable matching rows shared by pages of one query. */
    private static final class Shared {
        final List<ArchiveRow<Projected>> rows;
        final List<String> issues;

        Shared(List<ArchiveRow<Projected>> rows, List<String> issues) { this.rows = List.copyOf(rows); this.issues = List.copyOf(issues); }

    }

    /**
     * One session's facts as last read ({@code stamp} null for the current session): every saved loot bag, and fame gains and
     * combat records by exact visit. A module that could not be read is null (its facts unknown) and named in {@code issues};
     * kept with the stamp like the rest, since reading the same files again fails the same way.
     */
    private record Facts(List<SessionStamps.Stamp> stamp, List<LootFacts.Bag> loot, Map<VisitRef, Long> fame,
                         Map<VisitRef, List<CombatRecord>> combat, List<String> issues) {}

    /** A projected dungeon run; duplicate saved visits remain separate rows. */
    private record Projected(ActivityQueries.Row run, RunOutcome outcome, Integer rosterSize) {}

    /** A session's runs and issues, independent of loaded card facts. */
    private record Runs(List<ArchiveRow<Projected>> rows, List<String> issues) {}

    /**
     * Reads journal and checkpoints with the same ended-session fix-up as the store. Neither reader deduplicates visits
     * or lets a checkpoint override a journal record. Rows whose sort keys tie keep this read order: checkpoints (by
     * file name), then journal lines. A non-regular or linked file is skipped, and an empty, null or oversized record fails the read.
     * Nothing is published to the cache until the whole session succeeds and cancellation is checked.
     */
    private Runs runs(SessionStore.SessionEntry entry, Cancellation cancel) throws IOException {
        reads.incrementAndGet();
        Path journal = store.directory().resolve(entry.id).resolve("runs.jsonl");
        Journal checked = journal(journal, cancel);
        List<ArchiveRow<Projected>> rows = new ArrayList<>();
        SessionStore.Session session = entry.session();
        boolean[] state = SessionFacts.state(session, entry.id.equals(store.currentId()));
        try {
            List<Path> checkpoints = new ArrayList<>();
            Path folder = journal.resolveSibling("runs");
            if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
                for (Path file : files) { cancel.check(); if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) checkpoints.add(file); }
            }
            checkpoints.sort(Comparator.naturalOrder());
            int ordinal = 0;
            for (Path file : checkpoints) {
                cancel.check();
                if (Files.size(file) > MAX_RECORD) throw new IOException("Checkpoint exceeds the 16 MiB record limit");
                ActivityJournal.Visit visit = SessionStore.JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), ActivityJournal.Visit.class);
                if (visit == null) throw new IOException("Null archive record in runs");   // as the archive pin read it
                project(rows, session, state, visit, ordinal++);
            }
            for (ActivityJournal.Visit visit : checked.visits) {
                cancel.check();
                project(rows, session, state, visit, ordinal++);
            }
        } catch (java.util.concurrent.CancellationException failure) { throw failure; }
        catch (RuntimeException failure) { throw new IOException("Unreadable runs record in session " + entry.id, failure); }
        unchangedPrefix(journal, checked.attributes);
        cancel.check();
        return new Runs(List.copyOf(rows), checked.tail ? List.of(entry.id + "/runs: unfinished journal tail excluded") : List.of());
    }

    private static void project(List<ArchiveRow<Projected>> rows, SessionStore.Session session, boolean[] state,
                                ActivityJournal.Visit visit, int ordinal) {
        if (visit.ended == 0 && session.ended > 0) { visit.ended = visit.lastSeen; visit.endReason = "App ended"; }
        if (!ParseDungeon.isDungeon(visit.map)) return;
        Projected projected = new Projected(ActivityQueries.visit(visit), RunOutcome.of(visit, state[0], state[1]), visit.rosterSize);
        String locator = String.format(Locale.ROOT, "%010d:", ordinal) + visit.id;   // ties follow read order, checkpoints first
        rows.add(new ArchiveRow<>(new ArchiveRow.Ref(session.id, "runs", locator, ""), projected));
    }

    /**
     * Reads and validates the '\n'-terminated lines of the initial byte prefix as the archive pin did: bytes are streamed and a
     * line is held only up to {@link #MAX_RECORD} (a longer complete line fails the read without being held whole), and each
     * line is decoded replacing malformed UTF-8. The bytes after the last '\n' are an unfinished tail, excluded even if they
     * hold valid JSON. Appends never enter this read; the resulting visits are reused rather than read again from the store.
     */
    private static Journal journal(Path file, Cancellation cancel) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return new Journal(null, List.of(), false);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        long bytes = attributes.size();
        List<ActivityJournal.Visit> visits = new ArrayList<>();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean oversized = false;
        int last = '\n';
        try (InputStream input = new BufferedInputStream(new JournalPrefix(Files.newInputStream(file), bytes))) {
            long offset = 0;
            for (int value; (value = input.read()) >= 0; last = value) {
                if ((offset++ & 4095) == 0) cancel.check();
                if (value == '\n') {
                    if (oversized) throw new IOException("Journal record exceeds the 16 MiB record limit");
                    visits.add(visit(new String(line.toByteArray(), StandardCharsets.UTF_8)));
                    line.reset();
                } else if (!oversized) {   // an oversized line is skipped to its end: it fails there, or is the excluded tail
                    if (line.size() >= MAX_RECORD) { oversized = true; line.reset(); } else line.write(value);
                }
            }
        }
        unchangedPrefix(file, attributes);
        cancel.check();
        return new Journal(attributes, visits, last != '\n');
    }

    /** One complete journal line as a visit; an empty, null or damaged line fails the read. */
    private static ActivityJournal.Visit visit(String json) throws IOException {
        ActivityJournal.Visit visit;
        try { visit = SessionStore.JSON.fromJson(json, ActivityJournal.Visit.class); }
        catch (RuntimeException failure) { throw new IOException("Unreadable runs journal record", failure); }
        if (visit == null) throw new IOException("Null archive record in runs");
        return visit;
    }

    private static void unchangedPrefix(Path file, BasicFileAttributes before) throws IOException {
        if (before == null) return; // A journal created later contributes nothing to this result.
        BasicFileAttributes after;
        try { after = Files.readAttributes(file, BasicFileAttributes.class); }
        catch (IOException failure) { throw new IOException("History source changed during read; retry", failure); }
        if (!after.isRegularFile() || after.size() < before.size()
                || !Objects.equals(before.fileKey(), after.fileKey()) || !before.creationTime().equals(after.creationTime()))
            throw new IOException("History source changed during read; retry");
    }

    private static final class JournalPrefix extends FilterInputStream {
        private long remaining;
        JournalPrefix(InputStream stream, long bytes) { super(stream); remaining = bytes; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = super.read();
            if (value < 0) throw new IOException("History source changed during read; retry");
            remaining--; return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = super.read(bytes, offset, (int) Math.min(remaining, length));
            if (count < 0) throw new IOException("History source changed during read; retry");
            remaining -= count; return count;
        }
    }

    private record Journal(BasicFileAttributes attributes, List<ActivityJournal.Visit> visits, boolean tail) {}
}
