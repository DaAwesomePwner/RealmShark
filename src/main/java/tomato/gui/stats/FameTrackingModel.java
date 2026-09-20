package tomato.gui.stats;

import java.util.*;
import tomato.gui.stats.data.MapFameData;
import tomato.gui.stats.session.FameSession;

/** Fame-only, non-I/O state. Snapshots detach collections and mutable map rows. */
final class FameTrackingModel {
    private FameTrackingModel() {}

    static final class Table {
        private final HashMap<Integer, Fame> last = new HashMap<>();
        private final HashMap<Integer, Double> initial = new HashMap<>();
        private final HashMap<Integer, Long> observed = new HashMap<>();
        private final HashMap<Integer, String> classes = new HashMap<>();
        private final HashMap<Integer, ArrayList<MapFameData>> closed = new HashMap<>();
        private final HashMap<Integer, MapFameData> open = new HashMap<>();
        private int currentId = -1, transitionId = -1;
        private String mapName = "";
        private long transitionTime, snapshots, copiedVisits;
        private double transitionFame;

        synchronized void sample(int id, long fame, long time, String className) {
            if (className != null && !className.isEmpty()) classes.put(id, className);
            Fame previous = last.get(id);
            if (previous != null && time < previous.getTime()) return;
            boolean sameCharacter = currentId == id;
            if (!sameCharacter) { finishMap(currentId); currentId = id; }
            initial.putIfAbsent(id, (double)fame);
            if (sameCharacter && previous != null) observed.merge(id, time - previous.getTime(), Long::sum);
            last.put(id, new Fame(fame, time));
            MapFameData map = open.get(id);
            if (map == null && !mapName.isEmpty()) {
                boolean baseline = transitionId == id && transitionTime <= time;
                map = new MapFameData(mapName, baseline ? transitionTime : time, baseline ? transitionFame : fame);
                open.put(id, map); transitionId = -1;
            }
            if (map != null) { map.endTime = Math.max(map.startTime, time); map.endFame = fame; }
        }

        synchronized void mapChanged(String name, long time) {
            MapFameData map = open.get(currentId);
            if (map != null) map.endTime = Math.max(map.endTime, time);
            finishMap(currentId);
            mapName = name;
            Fame baseline = last.get(currentId);
            transitionId = baseline == null ? -1 : currentId;
            transitionTime = time; transitionFame = baseline == null ? 0 : baseline.getFame();
            // The next sample identifies the character. Do not assign the old character's
            // baseline to a new character arriving with the same map-change packet.
        }

        private void finishMap(int id) {
            MapFameData map = open.remove(id);
            if (map != null) closed.computeIfAbsent(id, key -> new ArrayList<>()).add(map);
        }

        synchronized void characterChanged(int id) {
            if (id != -1 && currentId != id) { finishMap(currentId); currentId = -1; }
        }

        synchronized void characterBaseline(int id, String className, long fame) {
            classes.put(id, className); initial.putIfAbsent(id, (double)fame);
        }

        synchronized void reset() {
            last.forEach((id, sample) -> initial.put(id, sample.getFame()));
            observed.clear(); closed.clear(); open.clear(); last.clear();
            currentId = transitionId = -1;
        }

        synchronized Double currentFame(int id) { return last.containsKey(id) ? last.get(id).getFame() : null; }
        synchronized String className(int id) { return classes.getOrDefault(id, "Char"); }
        synchronized HashMap<Integer, String> classNames() { return new HashMap<>(classes); }

        synchronized TableSnapshot snapshot(boolean includeMaps) {
            snapshots++;
            HashMap<Integer, MapFameData> current = includeMaps ? currentMaps() : new HashMap<>();
            HashMap<Integer, ArrayList<MapFameData>> maps = includeMaps ? copyClosed() : new HashMap<>();
            current.forEach((id, row) -> maps.computeIfAbsent(id, key -> new ArrayList<>()).add(row));
            return new TableSnapshot(currentId, new HashMap<>(initial), new HashMap<>(last),
                new HashMap<>(observed), new HashMap<>(classes), maps, current);
        }

        synchronized HashMap<Integer, ArrayList<MapFameData>> maps() {
            HashMap<Integer, ArrayList<MapFameData>> result = copyClosed();
            currentMaps().forEach((id, row) -> result.computeIfAbsent(id, key -> new ArrayList<>()).add(row));
            return result;
        }

        private HashMap<Integer, ArrayList<MapFameData>> copyClosed() {
            HashMap<Integer, ArrayList<MapFameData>> result = new HashMap<>();
            closed.forEach((id, rows) -> {
                ArrayList<MapFameData> copies = new ArrayList<>();
                for (MapFameData row : rows) { copies.add(copy(row)); copiedVisits++; }
                result.put(id, copies);
            });
            return result;
        }

        synchronized HashMap<Integer, MapFameData> currentMaps() {
            HashMap<Integer, MapFameData> result = new HashMap<>();
            open.forEach((id, row) -> { result.put(id, copy(row)); copiedVisits++; });
            return result;
        }

        synchronized void setMaps(HashMap<Integer, ArrayList<MapFameData>> data) {
            closed.clear();
            data.forEach((id, rows) -> {
                ArrayList<MapFameData> copies = new ArrayList<>();
                for (MapFameData row : rows) copies.add(copy(row));
                closed.put(id, copies);
            });
        }

        synchronized void setCurrentMaps(HashMap<Integer, MapFameData> data) {
            open.clear(); data.forEach((id, row) -> open.put(id, copy(row)));
        }

        synchronized TableCounts counts() {
            int visits = 0;
            for (ArrayList<MapFameData> rows : closed.values()) visits += rows.size();
            return new TableCounts(initial.size(), last.size(), visits, open.size(), snapshots, copiedVisits);
        }
    }

    static final class TableSnapshot {
        final int currentId;
        final HashMap<Integer, Double> initial;
        final HashMap<Integer, Fame> last;
        final HashMap<Integer, Long> observed;
        final HashMap<Integer, String> classes;
        final HashMap<Integer, ArrayList<MapFameData>> maps;
        final HashMap<Integer, MapFameData> open;

        TableSnapshot(int currentId, HashMap<Integer, Double> initial, HashMap<Integer, Fame> last,
                      HashMap<Integer, Long> observed, HashMap<Integer, String> classes,
                      HashMap<Integer, ArrayList<MapFameData>> maps, HashMap<Integer, MapFameData> open) {
            this.currentId = currentId; this.initial = initial; this.last = last; this.observed = observed;
            this.classes = classes; this.maps = maps; this.open = open;
        }

        String displayName(int id) { return classes.getOrDefault(id, "Char") + " (#" + id + ")"; }
        double gain(int id) { return last.containsKey(id) ? last.get(id).getFame() - initial.getOrDefault(id, last.get(id).getFame()) : 0; }
    }

    /** Retained logical rows and model-copy counts (including explicit reads and saves). */
    static final class TableCounts {
        final int characters, lastSamples, closedVisits, openVisits;
        final long snapshots, copiedVisits;
        TableCounts(int characters, int lastSamples, int closedVisits, int openVisits, long snapshots, long copiedVisits) {
            this.characters = characters; this.lastSamples = lastSamples; this.closedVisits = closedVisits;
            this.openVisits = openVisits; this.snapshots = snapshots; this.copiedVisits = copiedVisits;
        }
    }

    static final class History {
        private final LinkedHashMap<Integer, ArrayList<Fame>> samples = new LinkedHashMap<>();
        private int currentId = -1;
        private long sampleCount, snapshots, copiedSamples, generation;
        private FameSession session;
        private boolean dirty;
        private long revision, lastTimestamp, request;
        private String status = "Sessions save automatically on map changes.";

        History() { resetSession(); }

        synchronized void sample(int id, long fame, long time) {
            samples.computeIfAbsent(id, key -> new ArrayList<>()).add(new Fame(fame, time));
            currentId = id; sampleCount++; revision++; dirty = true;
        }

        synchronized boolean sampleIfChanged(int id, long fame, long time) {
            ArrayList<Fame> rows = samples.get(id);
            if (currentId == id && rows != null && rows.get(rows.size() - 1).getFame() == fame) return false;
            sample(id, fame, time); return true;
        }

        synchronized GraphSnapshot graph(Integer selectedId, long duration) {
            snapshots++;
            int id = selectedId == null ? currentId : selectedId;
            ArrayList<Fame> rows = samples.get(id), selected = new ArrayList<>();
            if (rows != null) {
                long latest = Long.MIN_VALUE;
                for (Fame row : rows) latest = Math.max(latest, row.getTime());
                for (Fame row : rows) if (duration == 0 || row.getTime() >= latest - duration) selected.add(row);
            }
            copiedSamples += selected.size();
            return new GraphSnapshot(new ArrayList<>(samples.keySet()), id, selected, generation);
        }

        synchronized HashMap<Integer, ArrayList<Fame>> samples() {
            HashMap<Integer, ArrayList<Fame>> result = new HashMap<>();
            samples.forEach((id, rows) -> { result.put(id, new ArrayList<>(rows)); copiedSamples += rows.size(); });
            return result;
        }

        synchronized HistoryCounts counts() { return new HistoryCounts(samples.size(), sampleCount, snapshots, copiedSamples); }
        synchronized boolean dirty() { return dirty; }
        synchronized boolean hasSamples() { return !samples.isEmpty(); }
        synchronized String status() { return status; }
        synchronized void visitsChanged() { revision++; if (hasSamples()) dirty = true; }
        synchronized void rename(String name) { session.setSessionName(name); revision++; }

        synchronized Save prepareSave(HashMap<Integer, ArrayList<MapFameData>> maps, HashMap<Integer, String> classes) {
            // The existing async saver detaches this transfer object before returning.
            // Never expose the model's history lists or open visits to that transfer.
            HashMap<Integer, List<Fame>> history = new HashMap<>();
            HashMap<Integer, String> names = new HashMap<>();
            samples.forEach((id, rows) -> {
                history.put(id, new ArrayList<>(rows)); copiedSamples += rows.size();
                names.put(id, classes.getOrDefault(id, "Char"));
            });
            HashMap<Integer, List<MapFameData>> visits = new HashMap<>();
            maps.forEach((id, rows) -> visits.put(id, new ArrayList<>(rows)));
            session.setCharacterFameData(history);
            session.setCharacterMapFameData(visits);
            session.setCharacterClassNames(names);
            status = "Saving fame session…";
            return new Save(session, revision, ++request);
        }

        synchronized void saved(Save saving, boolean success, boolean resetOnSuccess) {
            if (session == saving.session) {
                if (success && revision == saving.revision) dirty = false;
                if (saving.request == request) status = success
                    ? (revision == saving.revision ? "Fame session saved locally." : "Fame session saved; newer samples await the next save.")
                    : "Fame save failed. Check FameSessions folder access; the next autosave will retry.";
                if (success && revision == saving.revision && resetOnSuccess) resetSession();
            } else if (!success) {
                status = "A previous fame session could not be saved. Check FameSessions folder access.";
            }
        }

        synchronized void startNew() {
            samples.clear(); sampleCount = 0; currentId = -1; generation++; resetSession();
        }

        synchronized String sessionName() { return session.getSessionName(); }
        synchronized long deleting() { status = "Clearing previous session file…"; return ++request; }
        synchronized void deleted(long deletingRequest, boolean success) {
            if (request == deletingRequest) status = success ? "Session cleared. Tracking a new session."
                : "Could not delete the previous session file. Check FameSessions folder access.";
        }

        private void resetSession() {
            lastTimestamp = Math.max(System.currentTimeMillis(), lastTimestamp + 1);
            session = new FameSession("Live_" + lastTimestamp); revision++; dirty = false;
        }
    }

    static final class GraphSnapshot {
        final ArrayList<Integer> characterIds;
        final int selectedId;
        final ArrayList<Fame> samples;
        final long generation;
        GraphSnapshot(ArrayList<Integer> characterIds, int selectedId, ArrayList<Fame> samples, long generation) {
            this.characterIds = characterIds; this.selectedId = selectedId; this.samples = samples; this.generation = generation;
        }
    }

    /** Full retained history count; copiedSamples also includes explicit reads and saves. */
    static final class HistoryCounts {
        final int characters;
        final long samples, snapshots, copiedSamples;
        HistoryCounts(int characters, long samples, long snapshots, long copiedSamples) {
            this.characters = characters; this.samples = samples; this.snapshots = snapshots; this.copiedSamples = copiedSamples;
        }
    }

    static final class Save {
        final FameSession session;
        final long revision, request;
        Save(FameSession session, long revision, long request) { this.session = session; this.revision = revision; this.request = request; }
    }

    private static MapFameData copy(MapFameData row) {
        MapFameData copy = new MapFameData(row.mapName, row.startTime, row.startFame);
        copy.endTime = row.endTime; copy.endFame = row.endFame; return copy;
    }
}
