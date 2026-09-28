package tomato.gui.dps;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.encounter.CombatRetention;
import tomato.history.link.VisitRef;

/**
 * The Recordings tab's reader (spec §6.3, §8.4): every combat recording of this app run's memory (captured, saved full detail
 * loaded back, user imports) and of saved history, one row per recording. Off the EDT only; the EDT applies the immutable
 * {@link Result}. Nothing is written.
 *
 * <p>Saved history: the {@code encounters} records of each readable session ({@link CombatFacts#readSession}) and whether each
 * record's full-detail file ({@link CombatAutosave#fullDetailFile}) is there. Retention deletes those files and never rewrites
 * the record, so a record saying full detail was kept whose file is gone is {@link RecordingItem.FullDetail#PRUNED}.
 * {@link RecordingsQuery.Scope#LAST_30_DAYS} lists the saved records entered in the last 30 days of the clock (a record without
 * an entry time is dated by its file, as {@link CombatRetention} dates it); a session that ended before then holds none, so it
 * is not read. A closed session's records are kept while its stamp (its metadata and the files of its encounters and
 * combat-full folders: name, size, modification time) is unchanged; the current session is read every time. A session whose
 * metadata, records folder or full-detail folder cannot be read is counted in {@link Result#sessionsSkipped()} and named in
 * {@link Result#issues()} (by session ID and failure kind, never a path); a damaged record file is skipped and counted.
 *
 * <p>Merging (research R2 §3c): a recording in memory, its saved record and its full-detail file are one row that opens the copy
 * in memory (captured: This app run; saved full detail loaded back: Saved). A saved record without a copy in memory is a Saved
 * row. Imports are always rows of their own keyed by their exact bytes ({@code file:<sha-256>}); one claiming a recording ID
 * another row has says so ({@link RecordingItem#sameRecordingAs()}), and one without a recording ID is a legacy import. Rows in
 * memory are listed in either scope, and the sessions of loaded saved full detail are always read so its record is found.
 * Facts come from the saved record when there is one, else from the recording itself through {@link CombatSummaries} (the same
 * definitions; once per in-memory entry, whose graph is frozen). Link state is the item's own: its verified visit, else
 * unlinked with an entry context, else legacy; never a name or time. Rows are newest first by entry time, else first tick,
 * else record file time; a tie lists this app run, then saved, then imports.
 */
public final class RecordingsSource {
    /** The {@link RecordingsQuery.Scope#LAST_30_DAYS} span. */
    public static final long RECENT_MILLIS = 30L * 24 * 60 * 60 * 1000;
    /** The one issue when the app has no saved history (only memory rows are listed). */
    public static final String NO_HISTORY = "Saved history is not available: only this app run's recordings and imports are listed.";

    /** One read: rows newest first, the sessions that could not be read, why the list may be partial, and the clock's time. */
    public record Result(List<RecordingItem> items, int sessionsSkipped, List<String> issues, long capturedAt) {
        public Result { items = List.copyOf(items); issues = List.copyOf(issues); }
    }

    /** {@link CombatFacts#readSession}'s shape; tests replace it to fail one session's read. */
    @FunctionalInterface interface RecordReader {
        int read(SessionStore store, SessionStore.SessionEntry entry, CombatFacts.Dated sink) throws IOException;
    }

    private final Supplier<SessionStore> store;
    private final Supplier<List<EncounterCatalog.Entry>> memory;
    private final LongSupplier clock;
    private volatile RecordReader records = CombatFacts::readSession;
    /** Closed sessions' facts by ID for {@link #keptRoot}; guarded by this. */
    private final Map<String, Facts> kept = new HashMap<>();
    private Path keptRoot;
    /** In-memory entries' summaries by entry ID (their graphs are frozen); guarded by this. */
    private final Map<String, CombatRecord> summaries = new HashMap<>();
    private final AtomicInteger summarized = new AtomicInteger();
    /** Reads from disk by session ID (tests); guarded by this. */
    private final Map<String, Integer> reads = new HashMap<>();

    /**
     * @param store  the app's history (null when there is none), read on each call
     * @param memory this app run's catalog entries ({@code DpsGUI.encounters().entries()}), read on each call
     * @param clock  epoch ms, for the scope and {@link Result#capturedAt()}
     */
    public RecordingsSource(Supplier<SessionStore> store, Supplier<List<EncounterCatalog.Entry>> memory, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store"); this.memory = Objects.requireNonNull(memory, "memory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The recordings matching {@code query}, newest first. Off the EDT; {@code cancel} stops between sessions. */
    public Result read(RecordingsQuery query, Cancellation cancel) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read recordings off the EDT");
        Objects.requireNonNull(query, "query"); Objects.requireNonNull(cancel, "cancel");
        long now = clock.getAsLong();
        Long cutoff = query.scope() == RecordingsQuery.Scope.ALL ? null : now - RECENT_MILLIS;
        List<EncounterCatalog.Entry> entries = new ArrayList<>();
        List<EncounterCatalog.Entry> listed = memory.get();
        if (listed != null) for (EncounterCatalog.Entry entry : listed) if (entry != null) entries.add(entry);
        Set<String> pinned = new HashSet<>();   // sessions of saved full detail in memory: always read
        for (EncounterCatalog.Entry entry : entries) if (entry.kind() == EncounterCatalog.Kind.SAVED && entry.origin.session != null) pinned.add(entry.origin.session);

        List<String> issues = new ArrayList<>();
        int[] skipped = {0};
        Map<String, Saved> saved = new LinkedHashMap<>();
        SessionStore history = store.get();
        if (history == null) issues.add(NO_HISTORY);
        else readSaved(history, cutoff, pinned, saved, issues, skipped, cancel);
        cancel.check();

        List<Row> rows = new ArrayList<>();
        Map<String, String> claimed = new HashMap<>();   // recording ID -> the key of the row that owns it
        for (EncounterCatalog.Entry entry : entries) {   // this app run and saved full detail loaded back: the copy in memory opens
            if (entry.imported()) continue;
            String id = entry.data.getRecordingId();
            if (id != null && claimed.containsKey(id)) continue;   // a second copy in memory (addSaved never makes one)
            String key = id != null ? id : EncounterCatalog.reference(entry);
            Row row = row(key, entry.kind() == EncounterCatalog.Kind.CAPTURED ? RecordingItem.Kind.THIS_RUN : RecordingItem.Kind.SAVED,
                id == null ? null : saved.remove(id), entry, null, issues);
            if (row != null) rows.add(row);
            if (id != null) claimed.put(id, key);
        }
        for (Saved record : saved.values()) {   // saved only
            if (!record.inScope()) continue;
            rows.add(row(record.record().recordingId, RecordingItem.Kind.SAVED, record, null, null, issues));
            claimed.put(record.record().recordingId, record.record().recordingId);
        }
        for (EncounterCatalog.Entry entry : entries) {   // imports: their own rows, keyed by their bytes
            if (!entry.imported()) continue;
            String id = entry.data.getRecordingId(), key = EncounterCatalog.reference(entry);
            Row row = row(key, id == null ? RecordingItem.Kind.LEGACY_IMPORT : RecordingItem.Kind.IMPORTED, null, entry,
                id == null ? null : claimed.get(id), issues);
            if (row != null) rows.add(row);
            if (id != null) claimed.putIfAbsent(id, key);
        }
        forgetSummaries(entries);

        rows.sort(Comparator.comparingLong(Row::time).reversed().thenComparing(row -> row.item().kind())
            .thenComparing(row -> row.item().key()));
        List<RecordingItem> items = new ArrayList<>(rows.size());
        for (Row row : rows) if (query.matches(row.item())) items.add(row.item());
        return new Result(items, skipped[0], issues, now);
    }

    /** How many times {@code session}'s records were read from disk (tests: kept sessions are not read again). */
    synchronized int reads(String session) { return reads.getOrDefault(session, 0); }

    /** In-memory recordings summarized so far (tests: each once). */
    int summarized() { return summarized.get(); }

    /** Replaces the record reader (tests: a session whose records folder cannot be listed). */
    void recordReader(RecordReader reader) { records = Objects.requireNonNull(reader, "reader"); }

    /** Saved records by recording ID, newest session first; the first session holding an ID keeps it (a copied folder). */
    private void readSaved(SessionStore history, Long cutoff, Set<String> pinned, Map<String, Saved> saved, List<String> issues,
                           int[] skipped, Cancellation cancel) {
        List<SessionStore.SessionEntry> catalog;
        try { catalog = history.catalog(cancel); }
        catch (IOException | RuntimeException failure) {
            rethrowCancel(failure);
            issues.add("Saved sessions could not be listed (" + failure.getClass().getSimpleName() + ")");
            return;
        }
        forgetGone(history, catalog);
        for (SessionStore.SessionEntry entry : catalog) {
            cancel.check();
            if (!entry.readable()) { skipped[0]++; issues.add(entry.id + ": session metadata could not be read"); continue; }
            SessionStore.Session session = entry.session();
            // A record is entered and written before its session ends: a session that ended before the scope holds none.
            if (cutoff != null && session.ended > 0 && session.ended < cutoff && !pinned.contains(entry.id) && !entry.id.equals(history.currentId())) continue;
            Facts facts = facts(history, entry);
            if (facts.failure() != null) { skipped[0]++; issues.add(entry.id + ": " + facts.failure()); continue; }
            if (facts.damaged() > 0) issues.add(entry.id + ": " + facts.damaged() + (facts.damaged() == 1 ? " combat record" : " combat records") + " could not be read");
            for (Dated dated : facts.records()) {
                CombatRecord record = dated.record();
                if (saved.containsKey(record.recordingId)) continue;
                long time = record.enteredAt != null ? record.enteredAt : dated.written();
                saved.put(record.recordingId, new Saved(record, entry.id, dated.written(),
                    facts.fullDetail().get(fullDetailName(record.recordingId)), cutoff == null || time >= cutoff));
            }
        }
    }

    /** One session's facts: kept while a closed session's stamp is unchanged; the current session's are read every time. */
    private Facts facts(SessionStore history, SessionStore.SessionEntry entry) {
        Path folder = history.directory().resolve(entry.id);
        if (entry.id.equals(history.currentId())) return read(history, entry, folder, null);
        List<Stamp> stamp;
        try { stamp = stamp(folder); }
        catch (IOException | RuntimeException failure) { rethrowCancel(failure); return Facts.failed(null, "session folder could not be listed (" + failure.getClass().getSimpleName() + ")"); }
        synchronized (this) {
            Facts known = kept.get(entry.id);
            if (known != null && known.stamp().equals(stamp)) return known;
        }
        Facts read = read(history, entry, folder, stamp);
        synchronized (this) { kept.put(entry.id, read); }   // a failure too: the same files fail the same way until they change
        return read;
    }

    private Facts read(SessionStore history, SessionStore.SessionEntry entry, Path folder, List<Stamp> stamp) {
        synchronized (this) { reads.merge(entry.id, 1, Integer::sum); }
        List<Dated> read = new ArrayList<>();
        int damaged;
        try { damaged = records.read(history, entry, (record, written) -> read.add(new Dated(record, written))); }
        catch (IOException | RuntimeException failure) {
            rethrowCancel(failure);
            return Facts.failed(stamp, CombatFacts.RECORDS + " could not be read (" + failure.getClass().getSimpleName() + ")");
        }
        Map<String, Long> full = new HashMap<>();
        try { for (Stamp file : list(folder.resolve(CombatRetention.FULL_DETAIL), "")) full.put(file.name(), file.size()); }
        catch (IOException | RuntimeException failure) {   // presence unknown: never guess "pruned"
            rethrowCancel(failure);
            return Facts.failed(stamp, CombatRetention.FULL_DETAIL + " could not be read (" + failure.getClass().getSimpleName() + ")");
        }
        return new Facts(stamp, List.copyOf(read), Map.copyOf(full), damaged, null);
    }

    /** One row, or null (with an issue) when an in-memory recording could not be summarized. */
    private Row row(String key, RecordingItem.Kind kind, Saved saved, EncounterCatalog.Entry entry, String sameAs, List<String> issues) {
        CombatRecord record = saved != null ? saved.record() : summary(entry, issues);
        if (record == null) return null;
        VisitRef visit = record.visit();
        RecordingItem.Link link = visit != null ? RecordingItem.Link.LINKED : record.enteredAt != null ? RecordingItem.Link.UNLINKED : RecordingItem.Link.LEGACY;
        Double localDps = null; String unavailable;
        if (record.localObjectId == null) unavailable = link == RecordingItem.Link.LEGACY ? RecordingItem.LEGACY_LOCAL : RecordingItem.UNVERIFIED_LOCAL;
        else {
            CombatRecord.PlayerLine local = record.local();
            // Without a row the verified local player dealt no recorded damage: 0 over a known window (RunCardModel's rule).
            localDps = record.dps(local != null ? local : new CombatRecord.PlayerLine());
            unavailable = localDps == null ? RecordingItem.NO_WINDOW : null;
        }
        RecordingItem.FullDetail full = saved == null ? RecordingItem.FullDetail.NONE : saved.fullBytes() != null ? RecordingItem.FullDetail.PRESENT
            : record.fullDetail ? RecordingItem.FullDetail.PRUNED : RecordingItem.FullDetail.NONE;
        RecordingItem item = new RecordingItem(key, record.recordingId, kind, record.map, record.mapName, record.enteredAt, record.startedAt,
            record.elapsedMs, record.windowSeconds, record.contributors, record.totalDamage, localDps, unavailable, link, visit,
            saved == null ? null : saved.session(), saved != null, full, saved == null ? null : saved.fullBytes(), sameAs,
            entry == null ? null : entry.id, entry != null && entry.imported() ? entry.origin.fileName : null);
        long time = record.enteredAt != null ? record.enteredAt : record.startedAt != null ? record.startedAt : saved != null ? saved.written() : Long.MIN_VALUE;
        return new Row(item, time);
    }

    /** An in-memory recording's facts with the saved summary's definitions, built once per entry. */
    private CombatRecord summary(EncounterCatalog.Entry entry, List<String> issues) {
        synchronized (this) { CombatRecord known = summaries.get(entry.id); if (known != null) return known; }
        CombatRecord built;
        try { built = CombatSummaries.build(entry.data).record(); }
        catch (RuntimeException failure) {
            issues.add((entry.imported() ? entry.origin.fileName : "A recording of this app run") + " could not be summarized ("
                + failure.getClass().getSimpleName() + ")");
            return null;
        }
        summarized.incrementAndGet();
        synchronized (this) { CombatRecord raced = summaries.putIfAbsent(entry.id, built); return raced != null ? raced : built; }
    }

    private synchronized void forgetSummaries(List<EncounterCatalog.Entry> entries) {
        Set<String> ids = new HashSet<>();
        for (EncounterCatalog.Entry entry : entries) ids.add(entry.id);
        summaries.keySet().retainAll(ids);
    }

    /** Forgets everything when the history folder changed, and the sessions that left the catalog. */
    private synchronized void forgetGone(SessionStore history, List<SessionStore.SessionEntry> catalog) {
        if (!history.directory().equals(keptRoot)) { kept.clear(); keptRoot = history.directory(); }
        Set<String> listed = new HashSet<>();
        for (SessionStore.SessionEntry entry : catalog) listed.add(entry.id);
        kept.keySet().retainAll(listed); reads.keySet().retainAll(listed);
    }

    private static String fullDetailName(String recordingId) { return CombatAutosave.fullDetailFile(Paths.get(""), recordingId).getFileName().toString(); }

    private static void rethrowCancel(Exception failure) {
        if (failure instanceof CancellationException) throw (CancellationException) failure;
    }

    /** The session's metadata and the files of its records and full-detail folders, by name. */
    private static List<Stamp> stamp(Path folder) throws IOException {
        List<Stamp> stamp = new ArrayList<>();
        Path meta = folder.resolve("session.json");
        if (Files.exists(meta, LinkOption.NOFOLLOW_LINKS)) stamp.add(stamp(meta, "session.json"));
        stamp.addAll(list(folder.resolve(CombatFacts.RECORDS), CombatFacts.RECORDS + "/"));
        stamp.addAll(list(folder.resolve(CombatRetention.FULL_DETAIL), CombatRetention.FULL_DETAIL + "/"));
        stamp.sort(Comparator.comparing(Stamp::name));
        return stamp;
    }

    /** A folder's entries (none when it is missing or not a folder). */
    private static List<Stamp> list(Path folder, String prefix) throws IOException {
        List<Stamp> files = new ArrayList<>();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return files;
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder)) {
            for (Path file : listing) {
                try { files.add(stamp(file, prefix + file.getFileName())); }
                catch (NoSuchFileException removed) { /* deleted while listing (retention) */ }
            }
        } catch (NoSuchFileException | NotDirectoryException deleted) { files.clear(); }
        return files;
    }

    private static Stamp stamp(Path file, String name) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Stamp(name, attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    /** One entry as last seen: its name inside the session folder, size and modification time (epoch ms). */
    private record Stamp(String name, long size, long modified) {}

    /** A saved record with its file's time. */
    private record Dated(CombatRecord record, long written) {}

    /**
     * One session's saved recordings as last read ({@code stamp} null for the current session): its records, its full-detail
     * files' sizes by file name, the damaged record files, or why it could not be read.
     */
    private record Facts(List<Stamp> stamp, List<Dated> records, Map<String, Long> fullDetail, int damaged, String failure) {
        static Facts failed(List<Stamp> stamp, String failure) { return new Facts(stamp, List.of(), Map.of(), 0, failure); }
    }

    /** A saved record in one read: its session, file time, full-detail file size (null when absent) and whether the scope lists it. */
    private record Saved(CombatRecord record, String session, long written, Long fullBytes, boolean inScope) {}

    /** A row and the time it sorts by. */
    private record Row(RecordingItem item, long time) {}
}
