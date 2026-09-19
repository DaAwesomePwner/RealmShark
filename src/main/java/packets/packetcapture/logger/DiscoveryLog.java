package packets.packetcapture.logger;

import packets.Packet;
import packets.PacketType;
import packets.data.*;
import packets.data.enums.StatType;
import packets.incoming.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Independent passive observer. Never retains Packet, payload, string stats or exception messages. */
public final class DiscoveryLog implements AutoCloseable {
    public static final DiscoveryLog INSTANCE = new DiscoveryLog(Paths.get("logs", "discovery"));
    static { Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::close, "Discovery log shutdown")); }
    public static final int EVENT_LIMIT = 1500, CACHE_LIMIT = 20000, DELTA_LIMIT = 24;
    private final Path directory;
    private DiscoveryWriter writer;
    private final ActivityStore activityStore;
    private final ActivityJournal activity;
    private long lastCheckpoint;
    private boolean activityChanged;
    private boolean enabled = true, saveToDisk = true;
    private int sampleMillis = 1000;
    private String runId = UUID.randomUUID().toString();
    private long area, total, sampledOut, deltaOmitted, evictions, internalErrors;
    private final Map<Integer, PacketRow> packets = new TreeMap<>();
    private final Map<Integer, StatRow> stats = new TreeMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final LinkedHashMap<Long, Long> previous = new LinkedHashMap<>();
    // Positive gameplay-only allowlist. Strings, identifiers and unknown stat values are withheld.
    private static final Set<Integer> SAFE_STATS = new HashSet<>();
    static {
        for (int[] range : new int[][] {{0,5},{7,22},{24,30},{46,53},{57,58},{64,68},{83,96},{102,103},{105,114},{116,120},{122,126},{130,146}})
            for (int i = range[0]; i <= range[1]; i++) SAFE_STATS.add(i);
    }
    public DiscoveryLog(Path directory) {
        this.directory = directory;
        activityStore = directory == null ? null : new ActivityStore(directory);
        activity = new ActivityJournal(activityStore == null ? null : activityStore.load());
    }
    public synchronized boolean isEnabled() { return enabled; }
    public synchronized boolean isSaving() { return saveToDisk; }
    public synchronized void setEnabled(boolean value) {
        if (enabled != value) { previous.clear(); area++; activity.boundary(System.currentTimeMillis(), "Collection paused / resumed"); checkpoint(); }
        enabled = value;
    }
    public synchronized void setSaving(boolean value) { saveToDisk = value; }
    public synchronized void setSampleMillis(int value) {
        if (value != 0 && value != 1000) throw new IllegalArgumentException("Unsupported sampling interval");
        sampleMillis = value;
    }
    public synchronized void clear() {
        packets.clear(); stats.clear(); events.clear(); previous.clear();
        total = sampledOut = deltaOmitted = evictions = internalErrors = area = 0;
        runId = UUID.randomUUID().toString();
        activity.clear(); activityChanged = true; checkpoint();
    }
    public synchronized void clearDiagnostics() {
        packets.clear(); stats.clear(); events.clear(); previous.clear();
        total = sampledOut = deltaOmitted = evictions = internalErrors = 0;
    }
    public synchronized ActivityJournal.State activityHistory() { return activitySnapshot(); }
    public synchronized void boundary() { if (enabled) { area++; previous.clear(); activity.boundary(System.currentTimeMillis(), "Connection boundary; completion unknown"); checkpoint(); } }

    public synchronized void decodeFailure(int id, int bytes, packets.reader.BufferReader reader, Exception error) {
        if (!enabled) return;
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("field", reader.field()); diagnostic.put("fieldStart", reader.fieldStart());
        diagnostic.put("offset", reader.getIndex()); diagnostic.put("errorType", error.getClass().getSimpleName());
        if (reader.declaredLength() != null) diagnostic.put("declaredLength", reader.declaredLength());
        try { record(id, bytes, null, "decode-error", reader.getRemainingBytes(), diagnostic); }
        catch (RuntimeException ignored) { internalErrors++; }
    }

    public synchronized void observe(int id, int bytes, Packet packet, String outcome, int remaining) {
        if (!enabled) return;
        try { record(id, bytes, packet, outcome, remaining, Collections.emptyMap()); }
        catch (RuntimeException ignored) { internalErrors++; } // Optional logging must not stop capture.
    }
    private void record(int id, int bytes, Packet packet, String outcome, int remaining, Map<String, Object> diagnostic) {
        if (id < 0 || id > 255) return; // Wire IDs are one byte; synthetic IP messages are excluded.
        long now = System.currentTimeMillis();
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
        activity.observe(packet, type, outcome, now, diagnostic); activityChanged = true;
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
            row.latest = new LinkedHashMap<>(values);
            Event event = new Event(runId, now, area, id, row.name, row.direction, bytes, outcome, remaining, values, deltas,
                row.count, sampleMillis, deltaOmitted);
            if (events.size() == EVENT_LIMIT) events.removeFirst();
            events.addLast(event);
            if (saveToDisk && directory != null) {
                if (writer == null) writer = new DiscoveryWriter(directory, 4 * 1024 * 1024);
                writer.offer(event);
            }
        } else sampledOut++;
        if (now - lastCheckpoint >= 10000) { checkpoint(); lastCheckpoint = now; }
    }
    private void checkpoint() {
        if (activityChanged && saveToDisk && activityStore != null) activityStore.offer(activitySnapshot());
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
        List<PacketRow> packetCopy = new ArrayList<>(); for (PacketRow row : packets.values()) packetCopy.add(new PacketRow(row));
        List<StatRow> statCopy = new ArrayList<>(); for (StatRow row : stats.values()) statCopy.add(new StatRow(row));
        return new Snapshot(runId, area, total, sampledOut, deltaOmitted, evictions, internalErrors,
            writer == null ? 0 : writer.dropped.get(), writer == null ? "" : writer.error(),
            enabled, saveToDisk, sampleMillis, packetCopy, statCopy, new ArrayList<>(events), activitySnapshot(),
            activityStore == null ? "" : activityStore.error());
    }
    @Override public synchronized void close() {
        enabled = false; activity.boundary(System.currentTimeMillis(), "App closed; completion unknown"); checkpoint();
        if (writer != null) writer.close(); if (activityStore != null) activityStore.close();
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
            latest=new LinkedHashMap<>(r.latest);
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
            this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values)); statChanges=Collections.unmodifiableList(new ArrayList<>(deltas));
        }
    }
    public static final class Snapshot {
        public final int schemaVersion=2;
        public final ActivityJournal.State activity;
        public final String activityWriterError;
        public final String runId, exportedAt=Instant.now().toString(), writerError;
        public final String scope="Passive decoded traffic only. Sampled events; counters include all observed frames while logging is enabled. Not a complete combat recording.";
        public final long area, total, sampledOut, deltaOmitted, cacheEvictions, observerErrors, diskDropped;
        public final boolean enabled, saving;
        public final int sampleMillis;
        public final List<PacketRow> packets;
        public final List<StatRow> stats;
        public final List<Event> events;
        Snapshot(String run, long area, long total, long sampled, long omitted, long evicted, long errors, long dropped, String error,
                 boolean enabled, boolean saving, int sampleMillis, List<PacketRow> packets, List<StatRow> stats, List<Event> events,
                 ActivityJournal.State activity, String activityWriterError) {
            runId=run; this.area=area; this.total=total; sampledOut=sampled; deltaOmitted=omitted; cacheEvictions=evicted; observerErrors=errors;
            diskDropped=dropped; writerError=error; this.enabled=enabled; this.saving=saving; this.sampleMillis=sampleMillis;
            this.packets=packets; this.stats=stats; this.events=events;
            this.activity=activity; this.activityWriterError=activityWriterError;
        }
    }
}
