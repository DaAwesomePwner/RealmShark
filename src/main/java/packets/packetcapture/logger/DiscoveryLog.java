package packets.packetcapture.logger;

import packets.Packet;
import packets.PacketType;
import packets.data.*;
import packets.data.enums.StatType;
import packets.incoming.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Passive diagnostics and gameplay history. Raw packets and account/chat fields are not retained. */
public final class DiscoveryLog implements AutoCloseable {
    public static final DiscoveryLog INSTANCE = new DiscoveryLog(Paths.get("logs", "discovery"), false);
    static { Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::close, "Discovery log shutdown")); }
    public static final int EVENT_LIMIT = 1500, CACHE_LIMIT = 20000, DELTA_LIMIT = 24, TRANSITION_LIMIT = 32;
    /** History modules this collector produces; their recording intervals are persisted as coverage. */
    public static final String[] RECORDED_MODULES = {"runs", "timeline"};
    private static final long INTERVAL_PERSIST_MILLIS = 60000;
    private final Path directory;
    private DiscoveryWriter writer;
    private final ActivityStore activityStore;
    private final ActivityJournal activity;
    private long lastCheckpoint;
    private boolean activityChanged;
    private long activityGeneration;
    private volatile boolean enabled = true, saveToDisk = true;
    private boolean historical;
    private long diagnosticsRevision, collectionRevision, diagnosticsCopies;
    private long observedDiskDropped;
    private String observedWriterError = "", observedActivityError = "";
    private int sampleMillis = 1000;
    private String runId = UUID.randomUUID().toString();
    private long area, total, sampledOut, deltaOmitted, evictions, internalErrors;
    // Retained-sample removals at EVENT_LIMIT; distinct from delta-cache evictions above.
    private long retentionEvictions;
    private final ArrayDeque<Transition> transitions = new ArrayDeque<>();
    // Observed collection interval: first and latest frame recorded since collection last (re)started.
    private tomato.history.SessionStore history;
    private long intervalStart, intervalLast, intervalPersisted;
    private volatile String coverageError = "";
    private final Map<Integer, PacketRow> packets = new TreeMap<>();
    private final Map<Integer, StatRow> stats = new TreeMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final LinkedHashMap<Long, Long> previous = new LinkedHashMap<>();
    // Ephemeral MAPINFO provenance: the exact clean packet object that established the active visit.
    // Only the object identity and the journal's visit ID are held; no packet bytes or fields are kept.
    private String historySession;
    private MapInfoPacket visitPacket;
    private String visitPacketId;
    // Positive gameplay-only allowlist. Strings, identifiers and unknown stat values are withheld.
    private static final Set<Integer> SAFE_STATS = new HashSet<>();
    static {
        for (int[] range : new int[][] {{0,5},{7,22},{24,30},{46,53},{57,58},{64,68},{83,96},{102,103},{105,114},{116,120},{122,126},{130,146}})
            for (int i = range[0]; i <= range[1]; i++) SAFE_STATS.add(i);
    }
    public DiscoveryLog(Path directory) {
        this(directory, true);
    }
    private DiscoveryLog(Path directory, boolean restore) {
        this.directory = directory;
        activityStore = directory == null ? null : new ActivityStore(directory);
        activity = new ActivityJournal(!restore || activityStore == null ? null : activityStore.load());
        if (!restore) {
            activity.archiveTo(tomato.history.AppHistory::run);
        }
    }
    public synchronized void attachHistory(tomato.history.SessionStore history) {
        historySession = history.currentId(); this.history = history;
        activity.archiveTo(visit -> history.put("runs", visit.id, visit));
        activity.archiveEventsTo(entry -> history.append("timeline", entry));
        history.collect("run", () -> {
            ActivityJournal.Visit visit;
            synchronized (DiscoveryLog.this) { visit = activity.activeVisit(); }
            if (visit != null) history.put("runs", visit.id, visit);
        });
    }
    private DiscoveryLog(ActivityJournal.State saved) {
        historical = true;
        directory = null; activityStore = null; enabled = false; saveToDisk = false;
        activity = new ActivityJournal(saved, false);
    }
    public static DiscoveryLog historyView(ActivityJournal.State saved) { return new DiscoveryLog(saved); }
    public boolean isEnabled() { return enabled; }
    public boolean isSaving() { return saveToDisk; }
    public boolean isHistorical() { return historical; }
    public synchronized void setEnabled(boolean value) {
        if (enabled != value) {
            if (!value) closeInterval("Collection paused");
            previous.clear(); area++; diagnosticsRevision++; collectionRevision++; forgetVisitPacket(); activityBoundary("Collection paused / resumed"); checkpoint();
            transition(value, value ? "Collection resumed" : "Collection paused");
        }
        enabled = value;
    }
    public synchronized void setSaving(boolean value) { if (saveToDisk != value) { saveToDisk = value; diagnosticsRevision++; } }
    public synchronized void setSampleMillis(int value) {
        if (value != 0 && value != 1000) throw new IllegalArgumentException("Unsupported sampling interval");
        if (sampleMillis != value) { sampleMillis = value; diagnosticsRevision++; }
    }
    public synchronized void clear() {
        closeInterval("Collected data cleared");
        packets.clear(); stats.clear(); events.clear(); previous.clear();
        total = sampledOut = deltaOmitted = evictions = internalErrors = area = retentionEvictions = 0;
        transition(enabled, "Collected data cleared");
        runId = UUID.randomUUID().toString();
        diagnosticsRevision++; collectionRevision++;
        forgetVisitPacket(); activity.clear(); markActivityChanged(); checkpoint();
    }
    public synchronized void clearDiagnostics() {
        if (!packets.isEmpty() || !stats.isEmpty() || !events.isEmpty() || internalErrors != 0) diagnosticsRevision++;
        packets.clear(); stats.clear(); events.clear(); previous.clear();
        total = sampledOut = deltaOmitted = evictions = internalErrors = retentionEvictions = 0;
    }
    public synchronized ActivityJournal.State activityHistory() { return activitySnapshot(); }
    public synchronized String currentVisitId() { return activity.currentVisitId(); }
    /**
     * The exact visit that this MAPINFO object established, or null. A reference is only returned
     * while that visit is still active, collection is enabled and a history session is attached;
     * paused collection, boundaries, clears, partial or failed MAPINFO and unrelated packets yield
     * none. Producers call this synchronously after {@link #observe} for the same packet.
     */
    public synchronized tomato.history.link.VisitRef visitForMap(MapInfoPacket packet) {
        if (packet == null || packet != visitPacket || !enabled || historySession == null || historySession.isEmpty()) return null;
        String active = activity.currentVisitId();
        return active.isEmpty() || !active.equals(visitPacketId) ? null : new tomato.history.link.VisitRef(historySession, active);
    }
    /**
     * The exact visit active right now in this collector's history session, or null when collection is
     * paused, no history is attached or no visit is active (for example after a boundary). Producers call
     * this synchronously at their own observation time; it is never reconstructed later.
     */
    public synchronized CurrentVisit currentVisit() {
        String active = activity.currentVisitId();
        if (!enabled || historySession == null || historySession.isEmpty() || active.isEmpty()) return null;
        return new CurrentVisit(new tomato.history.link.VisitRef(historySession, active), activity.currentVisitMap());
    }
    public static final class CurrentVisit {
        public final tomato.history.link.VisitRef visit;
        /** Canonical map name recorded by the journal; may be null. */
        public final String map;
        CurrentVisit(tomato.history.link.VisitRef visit, String map) { this.visit = visit; this.map = map; }
    }
    private void forgetVisitPacket() { visitPacket = null; visitPacketId = null; }
    public synchronized void inspectPlayer(tomato.backend.data.Entity entity) {
        if (enabled && activity.inspectPlayer(new tomato.backend.data.InspectSnapshot(entity))) markActivityChanged();
    }
    public synchronized void inspectDamage(tomato.backend.data.Entity entity, long damage, long time) {
        if (!enabled || damage <= 0) return;
        if (!activity.hasInspectedPlayer(entity.id)) inspectPlayer(entity);
        if (activity.inspectDamage(entity.id, damage, time)) markActivityChanged();
    }
    public synchronized void completionStats(int characterId, int[] counts) {
        if (enabled) {
            long before = activity.revision();
            activity.completionStats(characterId, counts);
            if (activity.revision() != before) markActivityChanged();
        }
    }
    public synchronized void boundary() {
        forgetVisitPacket();
        if (enabled) {
            closeInterval("Connection boundary");
            area++; diagnosticsRevision++; previous.clear(); activityBoundary("Connection boundary; completion unknown"); checkpoint();
            transition(true, "Connection boundary; frames before and after are separate intervals");
        }
    }
    private void transition(boolean collecting, String reason) {
        if (transitions.size() == TRANSITION_LIMIT) transitions.removeFirst();
        transitions.addLast(new Transition(System.currentTimeMillis(), collecting, reason));
        diagnosticsRevision++;
    }
    /** Ends the observed interval and persists it asynchronously; frames outside intervals were not recorded. */
    private void closeInterval(String end) {
        if (intervalStart > 0) persistInterval(end);
        intervalStart = intervalLast = intervalPersisted = 0;
    }
    private void persistInterval(String end) {
        tomato.history.SessionStore target = history;
        if (target == null || !target.writable() || historical) return;
        long from = intervalStart, until = intervalLast;
        for (String module : RECORDED_MODULES) target.recordInterval(module, from, until, end).whenComplete((ignored, failure) -> {
            coverageError = failure == null ? "" : "Recording coverage could not be saved: " + failure.getClass().getSimpleName();
        });
        intervalPersisted = until;
    }
    private void activityBoundary(String reason) {
        long before=activity.revision(); activity.boundary(System.currentTimeMillis(),reason);
        if (before!=activity.revision()) markActivityChanged();
    }
    private void markActivityChanged() { activityChanged=true; activityGeneration++; }

    public synchronized void decodeFailure(int id, int bytes, packets.reader.BufferReader reader, Exception error) {
        if (id == PacketType.MAPINFO.getIndex()) forgetVisitPacket();
        if (!enabled) return;
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("field", reader.field()); diagnostic.put("fieldStart", reader.fieldStart());
        diagnostic.put("offset", reader.getIndex()); diagnostic.put("errorType", error.getClass().getSimpleName());
        if (reader.declaredLength() != null) diagnostic.put("declaredLength", reader.declaredLength());
        try { record(id, bytes, null, "decode-error", reader.getRemainingBytes(), diagnostic); }
        catch (RuntimeException ignored) { internalErrors++; diagnosticsRevision++; }
    }

    public synchronized void observe(int id, int bytes, Packet packet, String outcome, int remaining) {
        // Any MAPINFO supersedes the previous provenance; only a clean one that starts a visit replaces it.
        if (id == PacketType.MAPINFO.getIndex() || packet instanceof MapInfoPacket) forgetVisitPacket();
        if (!enabled) return;
        try { record(id, bytes, packet, outcome, remaining, Collections.emptyMap()); }
        catch (RuntimeException ignored) { internalErrors++; diagnosticsRevision++; } // Optional logging must not stop capture.
    }
    private void record(int id, int bytes, Packet packet, String outcome, int remaining, Map<String, Object> diagnostic) {
        if (id < 0 || id > 255) return; // Wire IDs are one byte; synthetic IP messages are excluded.
        diagnosticsRevision++;
        long now = System.currentTimeMillis();
        if (intervalStart == 0) intervalStart = now;
        intervalLast = now;
        if (now - intervalPersisted >= INTERVAL_PERSIST_MILLIS) persistInterval("Open; collection continuing");
        PacketType type = PacketType.byOrdinal(id);
        PacketRow row = packets.computeIfAbsent(id, n -> new PacketRow(n, type));
        total++; row.count++; row.bytes += Math.max(0, bytes); row.lastSeen = now;
        if (row.firstSeen == 0) row.firstSeen = now;
        row.minBytes = Math.min(row.minBytes, bytes); row.maxBytes = Math.max(row.maxBytes, bytes);
        if ("decode-error".equals(outcome)) row.failures++;
        if ("trailing-bytes".equals(outcome)) row.trailing++;
        boolean sample = DiscoveryCatalog.important(type) || !"decoded".equals(outcome)
            || row.lastSample == 0 || now - row.lastSample >= sampleMillis || !outcome.equals(row.lastOutcome);
        row.lastOutcome = outcome;
        List<Delta> deltas = new ArrayList<>();
        boolean clean = "decoded".equals(outcome);
        String visitBefore = activity.currentVisitId();
        activity.observe(packet, type, outcome, now, diagnostic); markActivityChanged();
        if (clean && packet instanceof MapInfoPacket) {
            String established = activity.currentVisitId();
            if (!established.isEmpty() && !established.equals(visitBefore)) { visitPacket = (MapInfoPacket)packet; visitPacketId = established; }
        }
        if (clean && packet instanceof MapInfoPacket) { area++; previous.clear(); }
        // Partial decodes are metadata only; their fields are not trusted as gameplay evidence.
        if (clean && packet instanceof UpdatePacket) {
            UpdatePacket update = (UpdatePacket)packet;
            if (update.drops != null && update.drops.length > 0) {
                Set<Integer> gone = new HashSet<>(); for (int object : update.drops) gone.add(object);
                previous.keySet().removeIf(key -> gone.contains((int)(key >> 32)));
            }
            if (update.newObjects != null) for (ObjectData object : update.newObjects)
                if (object != null) status(object.status, object.objectType, deltas, sample);
        } else if (clean && packet instanceof NewTickPacket) {
            ObjectStatusData[] statuses = ((NewTickPacket)packet).status;
            if (statuses != null) for (ObjectStatusData status : statuses) status(status, -1, deltas, sample);
        }
        if (sample) {
            row.lastSample = now;
            Map<String, Object> values = clean && packet != null ? DiscoveryCatalog.values(packet, type) : diagnostic;
            row.latest = ActivityJournal.copyValues(values);
            Event event = new Event(runId, now, area, id, row.name, row.direction, bytes, outcome, remaining, values, deltas,
                row.count, sampleMillis, deltaOmitted);
            if (events.size() == EVENT_LIMIT) { events.removeFirst(); retentionEvictions++; }
            events.addLast(event);
            if (saveToDisk && directory != null) {
                if (writer == null) writer = new DiscoveryWriter(directory, 4 * 1024 * 1024);
                writer.offer(event);
            }
        } else sampledOut++;
        if (now - lastCheckpoint >= 10000) { checkpoint(); lastCheckpoint = now; }
    }
    private void checkpoint() {
        if (this == INSTANCE && tomato.history.AppHistory.store() != null) return;
        // Only a coalesced request crosses the capture lock. The store acquires/copies on its worker,
        // then serializes and performs filesystem I/O after releasing this observer's monitor.
        if (activityChanged && saveToDisk && activityStore != null) {
            long generation=activityGeneration;
            activityStore.offer(this::activityHistory,()->{
                // Failed writes leave the generation dirty for the next checkpoint, including close.
                // An older successful write must not acknowledge changes made after it was queued.
                synchronized (DiscoveryLog.this) { if (activityGeneration==generation) activityChanged=false; }
            });
        }
    }
    private ActivityJournal.State activitySnapshot() {
        ActivityJournal.State state = activity.snapshot(); state.captureRunId = runId; state.checkpointTime = Instant.now().toString();
        for (PacketRow row : packets.values()) { state.packetCounts.put(row.name, row.count); state.decodeFailures.put(row.name, row.failures); }
        for (StatRow row : stats.values()) state.statObservations.put(Integer.toString(row.id), row.observations);
        return state;
    }
    private void status(ObjectStatusData status, int objectType, List<Delta> deltas, boolean sample) {
        if (status == null || status.stats == null) return;
        for (StatData stat : status.stats) {
            if (stat == null || stat.statTypeNum < 0 || stat.statTypeNum > 255) continue;
            StatRow row = stats.computeIfAbsent(stat.statTypeNum, StatRow::new);
            row.observations++;
            if (!SAFE_STATS.contains(stat.statTypeNum) || stat.stringStatValue != null) { row.withheld++; continue; }
            row.latest = stat.statValue; row.latestSecondary = stat.statValueTwo;
            row.min = row.min == null ? stat.statValue : Math.min(row.min, stat.statValue);
            row.max = row.max == null ? stat.statValue : Math.max(row.max, stat.statValue);
            long key = ((long)status.objectId << 32) | (stat.statTypeNum & 0xffffffffL);
            long value = ((long)stat.statValue << 32) | (stat.statValueTwo & 0xffffffffL);
            Long before = previous.put(key, value);
            if (previous.size() > CACHE_LIMIT) { previous.remove(previous.keySet().iterator().next()); evictions++; }
            if (before != null && before.longValue() != value) row.changes++;
            if (before == null || before.longValue() != value) {
                if (sample && deltas.size() < DELTA_LIMIT)
                    deltas.add(new Delta(status.objectId, objectType, stat.statTypeNum, before, value));
                else deltaOmitted++;
            }
        }
    }
    public synchronized Snapshot snapshot() {
        return snapshot(true);
    }
    private Snapshot snapshot(boolean includeActivity) {
        List<PacketRow> packetCopy = new ArrayList<>(); for (PacketRow row : packets.values()) packetCopy.add(new PacketRow(row));
        List<StatRow> statCopy = new ArrayList<>(); for (StatRow row : stats.values()) statCopy.add(new StatRow(row));
        List<Event> eventCopy = new ArrayList<>(); for (Event event : events) eventCopy.add(new Event(event));
        return new Snapshot(runId, area, total, sampledOut, deltaOmitted, evictions, internalErrors,
            writer == null ? 0 : writer.dropped.get(), writer == null ? "" : writer.error(),
            enabled, saveToDisk, sampleMillis, packetCopy, statCopy, eventCopy, includeActivity ? activitySnapshot() : null,
            activityStore == null ? "" : activityStore.error(), retentionEvictions,
            events.isEmpty() ? null : Instant.parse(events.peekFirst().timestamp).toEpochMilli(),
            events.isEmpty() ? null : Instant.parse(events.peekLast().timestamp).toEpochMilli(),
            intervalStart == 0 ? null : intervalStart, new ArrayList<>(transitions), coverageError);
    }
    /** A collection-state change; transitions are bounded to the most recent {@link #TRANSITION_LIMIT}. */
    public static final class Transition {
        public final long time;
        public final boolean collecting;
        public final String reason;
        Transition(long time, boolean collecting, String reason) { this.time = time; this.collecting = collecting; this.reason = reason; }
    }
    public static final class DiagnosticsRevision {
        private final transient DiscoveryLog owner;
        private final long revision;
        private DiagnosticsRevision(DiscoveryLog owner,long revision) { this.owner=owner; this.revision=revision; }
    }
    public static final class DiagnosticsSnapshot {
        public final DiagnosticsRevision revision;
        /** Activity is deliberately absent; this is a detached diagnostic payload. */
        public final Snapshot data;
        private DiagnosticsSnapshot(DiagnosticsRevision revision,Snapshot data) { this.revision=revision; this.data=data; }
    }
    /** Null means unchanged: no rows, events, or activity history were copied. */
    public synchronized DiagnosticsSnapshot diagnosticsSnapshot(DiagnosticsRevision known) {
        long dropped=writer==null ? 0 : writer.dropped.get();
        String error=writer==null ? "" : writer.error(), activityError=activityStore==null ? "" : activityStore.error();
        if (dropped!=observedDiskDropped || !error.equals(observedWriterError) || !activityError.equals(observedActivityError)) {
            observedDiskDropped=dropped; observedWriterError=error; observedActivityError=activityError; diagnosticsRevision++;
        }
        if (known!=null && known.owner==this && known.revision==diagnosticsRevision) return null;
        diagnosticsCopies++;
        return new DiagnosticsSnapshot(new DiagnosticsRevision(this,diagnosticsRevision),snapshot(false));
    }
    public static final class ActivityRevision {
        private final transient DiscoveryLog owner;
        private final long collection;
        private final ActivityJournal.ViewRevision view;
        private ActivityRevision(DiscoveryLog owner,long collection,ActivityJournal.ViewRevision view) { this.owner=owner; this.collection=collection; this.view=view; }
    }
    public static final class ActivitySnapshot {
        public final ActivityRevision revision;
        public final ActivityJournal.ViewSnapshot view;
        public final boolean enabled;
        private final ActivityJournal.State metadata;
        private ActivitySnapshot(ActivityRevision revision,ActivityJournal.ViewSnapshot view,boolean enabled,ActivityJournal.State metadata) {
            this.revision=revision; this.view=view; this.enabled=enabled; this.metadata=metadata;
        }
        /** Full export of the displayed revision, even after capture advances, clears or trims. */
        public ActivityJournal.State fullHistory() {
            ActivityJournal.State state=view.fullHistory(); state.captureRunId=metadata.captureRunId; state.checkpointTime=metadata.checkpointTime;
            state.packetCounts.putAll(metadata.packetCounts); state.decodeFailures.putAll(metadata.decodeFailures); state.statObservations.putAll(metadata.statObservations);
            return state;
        }
    }
    public synchronized ActivitySnapshot activityView(ActivityJournal.View view,String visitId,ActivityRevision known) {
        ActivityJournal.ViewRevision previous=known!=null && known.owner==this && known.collection==collectionRevision ? known.view : null;
        ActivityJournal.ViewSnapshot next=activity.viewSnapshot(view,visitId,previous);
        if (next==null) return null;
        ActivityJournal.State metadata=new ActivityJournal.State(); metadata.captureRunId=runId; metadata.checkpointTime=Instant.now().toString();
        for (PacketRow row:packets.values()) { metadata.packetCounts.put(row.name,row.count); metadata.decodeFailures.put(row.name,row.failures); }
        for (StatRow row:stats.values()) metadata.statObservations.put(Integer.toString(row.id),row.observations);
        return new ActivitySnapshot(new ActivityRevision(this,collectionRevision,next.revision),next,enabled,metadata);
    }
    public synchronized ActivityJournal.SnapshotStats activitySnapshotStats() { return activity.snapshotStats(); }
    public synchronized long diagnosticsSnapshotCopies() { return diagnosticsCopies; }
    @Override public void close() {
        DiscoveryWriter closingWriter;
        synchronized (this) {
            closeInterval("App closed");
            if (enabled) { enabled=false; diagnosticsRevision++; collectionRevision++; transition(false, "App closed"); }
            forgetVisitPacket(); activityBoundary("App closed; completion unknown"); checkpoint(); closingWriter=writer;
        }
        // Never join filesystem workers while holding the monitor used by capture or snapshots.
        if (closingWriter != null) closingWriter.close(); if (activityStore != null) activityStore.close();
    }

    public static final class PacketRow {
        public final int id;
        public final String name, direction;
        public long count, bytes, failures, trailing, firstSeen, lastSeen;
        public int minBytes = Integer.MAX_VALUE, maxBytes;
        private long lastSample;
        public String lastOutcome = "";
        public Map<String, Object> latest = Collections.emptyMap();
        PacketRow(int id, PacketType type) { this.id = id; name = type == null ? "UNKNOWN_ID_" + id : type.name(); direction = DiscoveryCatalog.direction(type); }
        PacketRow(PacketRow r) {
            id=r.id; name=r.name; direction=r.direction; count=r.count; bytes=r.bytes; failures=r.failures; trailing=r.trailing;
            firstSeen=r.firstSeen; lastSeen=r.lastSeen; minBytes=r.minBytes; maxBytes=r.maxBytes; lastOutcome=r.lastOutcome;
            latest=ActivityJournal.copyValues(r.latest);
        }
    }
    public static final class StatRow {
        public final int id;
        public final String name;
        public long observations, changes, withheld;
        public Integer latest, latestSecondary, min, max;
        StatRow(int id) { this.id=id; StatType type=StatType.byOrdinal(id); name=id==114 ? "OWNER_LINK_CANDIDATE (unverified)" : type==null ? "UNMAPPED_STAT_"+id : type.name(); }
        StatRow(StatRow r) { id=r.id; name=r.name; observations=r.observations; changes=r.changes; withheld=r.withheld; latest=r.latest; latestSecondary=r.latestSecondary; min=r.min; max=r.max; }
    }
    public static final class Delta {
        public final int objectId, objectTypeAtSpawn, statId, value, secondary;
        public final Integer previousValue, previousSecondary;
        Delta(int objectId, int objectType, int statId, Long previous, long next) {
            this.objectId=objectId; objectTypeAtSpawn=objectType; this.statId=statId;
            previousValue=previous==null?null:(int)(previous>>32); previousSecondary=previous==null?null:previous.intValue();
            value=(int)(next>>32); secondary=(int)next;
        }
    }
    public static final class Event {
        public final String runId, timestamp, packet, direction, outcome;
        public final long area;
        public final long observedPacketCount, totalStatSamplesOmitted;
        public final int sampleMillis;
        public final int id, bytes, remainingBytes;
        public final Map<String, Object> values;
        public final List<Delta> statChanges;
        Event(String run, long now, long area, int id, String name, String direction, int bytes, String outcome, int remaining, Map<String,Object> values, List<Delta> deltas,
              long observedCount, int sampleMillis, long omitted) {
            runId=run; timestamp=Instant.ofEpochMilli(now).toString(); this.area=area; this.id=id; packet=name; this.direction=direction;
            this.bytes=bytes; this.outcome=outcome; remainingBytes=remaining;
            observedPacketCount=observedCount; this.sampleMillis=sampleMillis; totalStatSamplesOmitted=omitted;
            this.values=Collections.unmodifiableMap(ActivityJournal.copyValues(values)); statChanges=Collections.unmodifiableList(new ArrayList<>(deltas));
        }
        Event(Event event) {
            runId=event.runId; timestamp=event.timestamp; area=event.area; id=event.id; packet=event.packet; direction=event.direction;
            bytes=event.bytes; outcome=event.outcome; remainingBytes=event.remainingBytes;
            observedPacketCount=event.observedPacketCount; sampleMillis=event.sampleMillis; totalStatSamplesOmitted=event.totalStatSamplesOmitted;
            values=Collections.unmodifiableMap(ActivityJournal.copyValues(event.values)); statChanges=event.statChanges; // Delta is immutable.
        }
    }
    public static final class Snapshot {
        public final int schemaVersion=2;
        public final ActivityJournal.State activity;
        public final String activityWriterError;
        public final String runId, exportedAt=Instant.now().toString(), writerError;
        public final String scope="Passive game-traffic diagnostics. Counters include observed frames, including decode failures, while gameplay & diagnostics collection is enabled. Retained events are sampled and bounded; not a complete combat recording.";
        public final long area, total, sampledOut, deltaOmitted, cacheEvictions, observerErrors, diskDropped;
        /** Retained samples removed at EVENT_LIMIT (history retention loss), not delta-cache evictions. */
        public final long retentionEvictions;
        /** Epoch-ms bounds of retained samples, null when none are retained; never continuous coverage. */
        public final Long retainedFirst, retainedLast;
        /** Start of the current observed collection interval, or null when nothing was observed since it began. */
        public final Long observedSince;
        public final List<Transition> transitions;
        public final String coverageError;
        public final boolean enabled, saving;
        public final int sampleMillis;
        public final List<PacketRow> packets;
        public final List<StatRow> stats;
        public final List<Event> events;
        Snapshot(String run, long area, long total, long sampled, long omitted, long evicted, long errors, long dropped, String error,
                 boolean enabled, boolean saving, int sampleMillis, List<PacketRow> packets, List<StatRow> stats, List<Event> events,
                 ActivityJournal.State activity, String activityWriterError, long retentionEvictions, Long retainedFirst, Long retainedLast,
                 Long observedSince, List<Transition> transitions, String coverageError) {
            this.retentionEvictions=retentionEvictions; this.retainedFirst=retainedFirst; this.retainedLast=retainedLast;
            this.observedSince=observedSince; this.transitions=Collections.unmodifiableList(transitions); this.coverageError=coverageError;
            runId=run; this.area=area; this.total=total; sampledOut=sampled; deltaOmitted=omitted; cacheEvictions=evicted; observerErrors=errors;
            diskDropped=dropped; writerError=error; this.enabled=enabled; this.saving=saving; this.sampleMillis=sampleMillis;
            this.packets=packets; this.stats=stats; this.events=events;
            this.activity=activity; this.activityWriterError=activityWriterError;
        }
    }
}
