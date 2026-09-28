package tomato.history.encounter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;

/**
 * Read-only access to saved combat summaries for pages (Home, the run feed and the recap). Off the EDT only. Links are
 * exact: a record belongs to a run only through {@link CombatRecord#visit()} equality, never by name or time.
 */
public final class CombatFacts {
    /** History modules: card records and their details, both keyed by recording ID. */
    public static final String RECORDS = "encounters", DETAILS = "encounter-detail";

    private CombatFacts() {}

    /**
     * Every readable record of the catalog's sessions in {@code scope} (a session ID or {@link SessionStore#ALL}), in
     * session and file-name order. Records with a newer {@code schemaVersion} or without a recording ID are skipped, and so
     * is a file that cannot be read or parsed: one bad file never fails the read. Unreadable sessions are skipped.
     */
    public static void read(SessionStore store, List<SessionStore.SessionEntry> catalog, String scope, Consumer<CombatRecord> sink) throws IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        for (SessionStore.SessionEntry entry : catalog) {
            if (!SessionStore.ALL.equals(scope) && !entry.id.equals(scope)) continue;
            if (!entry.readable()) continue;
            Path folder = store.directory().resolve(entry.id).resolve(RECORDS);
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder, "*.json")) { for (Path file : listing) files.add(file); }
            catch (NoSuchFileException | NotDirectoryException deleted) { continue; } // the session was deleted meanwhile
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                CombatRecord record;
                try { record = SessionStore.JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), CombatRecord.class); }
                catch (IOException | RuntimeException unreadable) { continue; } // removed while listing, or damaged: skip this file only
                if (record == null || record.schemaVersion > CombatRecord.SCHEMA_VERSION || record.recordingId == null || record.recordingId.isEmpty()) continue;
                if (record.players == null) record.players = new ArrayList<>();
                if (record.bosses == null) record.bosses = new ArrayList<>();
                sink.accept(record);
            }
        }
    }

    /**
     * The saved detail of one recording in {@code session}, or null when it is absent, of a newer schema, for another
     * recording, or {@code session} is not a session ID. A damaged file is an IOException, not an absence.
     */
    public static CombatDetail detail(SessionStore store, String session, String recordingId) throws IOException {
        if (session == null || recordingId == null || recordingId.isEmpty()) return null;
        Optional<CombatDetail> saved;
        try { saved = store.readCheckpoint(session, DETAILS, recordingId, CombatDetail.class); }
        catch (IllegalArgumentException notASession) { return null; }
        CombatDetail detail = saved.orElse(null);
        if (detail == null || detail.schemaVersion > CombatDetail.SCHEMA_VERSION || !recordingId.equals(detail.recordingId)) return null;
        if (detail.series == null) detail.series = new ArrayList<>();
        if (detail.sources == null) detail.sources = new ArrayList<>();
        if (detail.enemies == null) detail.enemies = new ArrayList<>();
        if (detail.deaths == null) detail.deaths = new ArrayList<>();
        return detail;
    }

    /**
     * The recording that represents one run: the longest known window, then the latest entry, then the greatest recording
     * ID (unknown window or entry ranks lowest). Null when there is none.
     */
    public static CombatRecord longest(Collection<CombatRecord> sameRun) {
        if (sameRun == null) return null;
        Comparator<CombatRecord> order = Comparator.comparing(CombatFacts::window, Comparator.nullsFirst(Comparator.<Double>naturalOrder()))
            .thenComparing(r -> r.enteredAt, Comparator.nullsFirst(Comparator.<Long>naturalOrder()))
            .thenComparing(r -> r.recordingId, Comparator.nullsFirst(Comparator.<String>naturalOrder()));
        CombatRecord best = null;
        for (CombatRecord record : sameRun) if (record != null && (best == null || order.compare(record, best) > 0)) best = record;
        return best;
    }

    private static Double window(CombatRecord record) {
        Double window = record.windowSeconds;
        return window != null && window > 0 && !window.isInfinite() ? window : null;
    }

    /** Records grouped by exact visit, in input order; records without a valid visit are left out. A new map. */
    public static Map<VisitRef, List<CombatRecord>> byVisit(Collection<CombatRecord> records) {
        Map<VisitRef, List<CombatRecord>> result = new LinkedHashMap<>();
        if (records != null) for (CombatRecord record : records) {
            VisitRef visit = record == null ? null : record.visit();
            if (visit != null) result.computeIfAbsent(visit, v -> new ArrayList<>()).add(record);
        }
        return result;
    }
}
