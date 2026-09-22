package tomato.gui.dps;

import java.util.*;
import tomato.backend.data.DpsData;

/** One source-local library. Entry identity survives dialogs; claimed recording IDs never replace entries. */
public final class EncounterCatalog {
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
        public String source() { return origin == null ? "Captured" : origin.fileName; }
    }
    public static final class Admission {
        public final Entry entry;
        public final boolean duplicate, sameRecordingId;
        Admission(Entry entry, boolean duplicate, boolean sameId) { this.entry = entry; this.duplicate = duplicate; sameRecordingId = sameId; }
    }
    private final Map<DpsData, Entry> captured = new IdentityHashMap<>();
    private final List<Entry> orderedCaptured = new ArrayList<>(), imports = new ArrayList<>();
    private final Map<String, Entry> fingerprints = new HashMap<>();
    private final Set<String> checked = new HashSet<>();
    private long revision;
    private long generation;
    public synchronized long revision() { return revision; }
    public synchronized long generation() { return generation; }
    /** Producer-owned array snapshot; importing never mutates the capture-owned history list. */
    public synchronized void captured(DpsData[] records) {
        List<Entry> next = new ArrayList<>(); Set<DpsData> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        for (DpsData record : records) if (record != null && retained.add(record)) next.add(captured.computeIfAbsent(record, d -> new Entry(d, null)));
        captured.keySet().retainAll(retained);
        if (!orderedCaptured.equals(next)) { orderedCaptured.clear(); orderedCaptured.addAll(next); pruneChecks(); revision++; }
    }
    public synchronized List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(orderedCaptured); entries.addAll(imports); return Collections.unmodifiableList(entries);
    }
    public synchronized Entry find(String id) { for (Entry entry : entries()) if (entry.id.equals(id)) return entry; return null; }
    public synchronized Entry find(DpsData data) { for (Entry entry : entries()) if (entry.data == data) return entry; return null; }
    public synchronized Admission add(EncounterImport candidate) {
        return add(candidate, generation);
    }
    public synchronized Admission add(EncounterImport candidate, long expectedGeneration) {
        if (generation != expectedGeneration) return null;
        Entry duplicate = fingerprints.get(candidate.fingerprint);
        if (duplicate != null) return new Admission(duplicate, true, false);
        String claimed = candidate.data.getRecordingId();
        boolean sameId = claimed != null && entries().stream().anyMatch(e -> claimed.equals(e.data.getRecordingId()));
        Entry entry = new Entry(candidate.data, candidate); imports.add(entry); fingerprints.put(candidate.fingerprint, entry); revision++;
        return new Admission(entry, false, sameId);
    }
    public synchronized void check(String id, boolean selected) { if (find(id) == null) return; if (selected) checked.add(id); else checked.remove(id); }
    public synchronized boolean checked(String id) { return checked.contains(id); }
    public synchronized List<Entry> checkedEntries() { List<Entry> result = new ArrayList<>(); for (Entry entry : entries()) if (checked.contains(entry.id)) result.add(entry); return result; }
    private void pruneChecks() { Set<String> ids = new HashSet<>(); for (Entry entry : entries()) ids.add(entry.id); checked.retainAll(ids); }
    public synchronized void clear() { captured.clear(); orderedCaptured.clear(); imports.clear(); fingerprints.clear(); checked.clear(); revision++; generation++; }
}
