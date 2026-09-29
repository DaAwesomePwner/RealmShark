package tomato.gui.dps;

import java.util.*;
import java.util.function.Predicate;
import tomato.backend.data.DpsData;

/**
 * One source-local library. Entry identity survives dialogs; claimed recording IDs never replace entries. Entries are this
 * app run's captured recordings, then user imports, then at most {@value #SAVED_KEPT} saved full-detail recordings loaded
 * back from history ({@link #addSaved}).
 */
public final class EncounterCatalog {
    /** Where an entry's recording came from: captured in this app run, a user's file, or saved full detail loaded back. */
    public enum Kind { CAPTURED, IMPORTED, SAVED }
    /** Saved full-detail entries kept in memory; a further load evicts the oldest one not on screen. */
    public static final int SAVED_KEPT = 2;

    public static final class Entry {
        public final String id = UUID.randomUUID().toString();
        public final DpsData data;
        public final EncounterImport origin;
        private volatile EncounterSummary summary;
        Entry(DpsData data, EncounterImport origin) { this.data = data; this.origin = origin; summary = origin == null ? null : origin.summary; }
        /** Archived graphs are frozen. Call on a background projection worker, not the EDT. */
        public EncounterSummary summary() {
            EncounterSummary value = summary;
            if (value == null) { value = new EncounterSummary(data); summary = value; }
            return value;
        }
        public Kind kind() { return origin == null ? Kind.CAPTURED : origin.kind; }
        /** True only for a user's import: saved full detail loaded back from history is not one. */
        public boolean imported() { return kind() == Kind.IMPORTED; }
        public String source() { return origin == null ? "Captured" : origin.kind == Kind.SAVED ? "Saved full detail" : origin.fileName; }
    }
    public static final class Admission {
        public final Entry entry;
        public final boolean duplicate, sameRecordingId;
        Admission(Entry entry, boolean duplicate, boolean sameId) { this.entry = entry; this.duplicate = duplicate; sameRecordingId = sameId; }
    }
    private final Map<DpsData, Entry> captured = new IdentityHashMap<>();
    private final List<Entry> orderedCaptured = new ArrayList<>(), imports = new ArrayList<>(), saved = new ArrayList<>();
    private final Map<String, Entry> fingerprints = new HashMap<>();
    private final Set<String> checked = new HashSet<>();
    private long revision;
    private long generation;
    private final String lifetimeId = UUID.randomUUID().toString();
    public String lifetimeId() { return lifetimeId; }
    public synchronized long revision() { return revision; }
    public synchronized long generation() { return generation; }
    /** Take the epoch before reading the producer's list; Clear can invalidate the read in flight. */
    public boolean capture(java.util.function.Supplier<DpsData[]> read) {
        long expectedGeneration = generation();
        return captured(read.get(), expectedGeneration);
    }
    /** Compatibility for already detached, single-threaded callers. Producers use capture(Supplier). */
    public synchronized void captured(DpsData[] records) {
        captured(records, generation);
    }
    public synchronized boolean captured(DpsData[] records, long expectedGeneration) {
        if (generation != expectedGeneration) return false;
        List<Entry> next = new ArrayList<>(); Set<DpsData> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        for (DpsData record : records) if (record != null && retained.add(record)) next.add(captured.computeIfAbsent(record, d -> new Entry(d, null)));
        captured.keySet().retainAll(retained);
        if (!orderedCaptured.equals(next)) { orderedCaptured.clear(); orderedCaptured.addAll(next); pruneChecks(); revision++; }
        return true;
    }
    public synchronized List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(orderedCaptured); entries.addAll(imports); entries.addAll(saved); return Collections.unmodifiableList(entries);
    }
    public synchronized Entry find(String id) { for (Entry entry : entries()) if (entry.id.equals(id)) return entry; return null; }
    public synchronized Entry find(DpsData data) { for (Entry entry : entries()) if (entry.data == data) return entry; return null; }
    public static String reference(Entry entry) {
        if (entry == null) return "";
        return entry.origin != null ? "file:" + entry.origin.fingerprint : entry.data.getRecordingId() != null
            ? "native:" + entry.data.getRecordingId() : "entry:" + entry.id;
    }
    /** Ambiguous claims are not a selection. Import references use exact original bytes. */
    public synchronized Entry resolve(String reference) {
        Entry match = null;
        for (Entry entry : entries()) if (reference(entry).equals(reference)) { if (match != null) return null; match = entry; }
        return match;
    }
    public synchronized Admission add(EncounterImport candidate) {
        return add(candidate, generation);
    }
    public synchronized Admission add(EncounterImport candidate, long expectedGeneration) {
        if (candidate.kind != Kind.IMPORTED) throw new IllegalArgumentException("Saved full detail is added with addSaved");
        if (generation != expectedGeneration) return null;
        Entry duplicate = fingerprints.get(candidate.fingerprint);
        if (duplicate != null) return new Admission(duplicate, true, false);
        String claimed = candidate.data.getRecordingId();
        boolean sameId = claimed != null && entries().stream().anyMatch(e -> claimed.equals(e.data.getRecordingId()));
        Entry entry = new Entry(candidate.data, candidate); imports.add(entry); fingerprints.put(candidate.fingerprint, entry); revision++;
        return new Admission(entry, false, sameId);
    }
    /** As {@link #addSaved(EncounterImport, Predicate, long)} at the current generation. */
    public synchronized Entry addSaved(EncounterImport candidate, Predicate<Entry> pinned) {
        return addSaved(candidate, pinned, generation);
    }
    /**
     * Admits saved full detail ({@link EncounterImport#readSaved}) and returns its entry, or null when Clear ran since
     * {@code expectedGeneration}. A captured or saved entry of the same recording ID is returned instead: the copy already in
     * memory wins, and a second one would make its routes ambiguous. At most {@value #SAVED_KEPT} saved entries stay: the
     * oldest one not {@code pinned} (the one on screen) is evicted, and none while every other one is pinned.
     */
    public synchronized Entry addSaved(EncounterImport candidate, Predicate<Entry> pinned, long expectedGeneration) {
        if (candidate.kind != Kind.SAVED) throw new IllegalArgumentException("A user's import is added with add");
        if (generation != expectedGeneration) return null;
        String claimed = candidate.data.getRecordingId();
        if (claimed != null) for (Entry entry : entries()) if (!entry.imported() && claimed.equals(entry.data.getRecordingId())) return entry;
        for (Iterator<Entry> oldest = saved.iterator(); saved.size() >= SAVED_KEPT && oldest.hasNext(); ) {
            Entry evicted = oldest.next();
            if (pinned != null && pinned.test(evicted)) continue;
            oldest.remove(); checked.remove(evicted.id);
        }
        Entry entry = new Entry(candidate.data, candidate); saved.add(entry); revision++;
        return entry;
    }
    public synchronized void check(String id, boolean selected) { if (find(id) == null) return; if (selected) checked.add(id); else checked.remove(id); }
    public synchronized boolean checked(String id) { return checked.contains(id); }
    public synchronized List<Entry> checkedEntries() { List<Entry> result = new ArrayList<>(); for (Entry entry : entries()) if (checked.contains(entry.id)) result.add(entry); return result; }
    private void pruneChecks() { Set<String> ids = new HashSet<>(); for (Entry entry : entries()) ids.add(entry.id); checked.retainAll(ids); }
    public synchronized void clear() { captured.clear(); orderedCaptured.clear(); imports.clear(); saved.clear(); fingerprints.clear(); checked.clear(); revision++; generation++; }
}
