package tomato.backend.data;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import packets.data.QuestData;
import packets.data.StatData;
import packets.data.enums.StatType;

/** Small, source-local publication mailbox. No live entities or credentials cross into views. */
public final class ProgressionData {
    public static final class Scope {
        public final long generation;
        public final String account, reason;
        public final boolean accepting;
        private Scope(long generation, String account, String reason, boolean accepting) {
            this.generation = generation; this.account = account; this.reason = reason; this.accepting = accepting;
        }
        public String description() {
            return (account == null ? "Account not verified" : "Account · " + account.substring(0, Math.min(6, account.length())))
                + " · Capture generation " + generation;
        }
    }
    public static final class Quests {
        public final Scope scope;
        public final long capturedAt;
        private final QuestData[] rows;
        private Quests(Scope scope, long at, QuestData[] rows) { this.scope = scope; capturedAt = at; this.rows = copyQuests(rows); }
        public QuestData[] rows() { return copyQuests(rows); }
    }
    public static final class Pet {
        public final Scope scope;
        public final int objectId;
        public final long capturedAt;
        public final String source;
        private final Stat stats;
        private final Map<Integer, FieldCapture> fields;
        private Pet(Scope scope, int objectId, long at, String source, Stat stats, Map<Integer, FieldCapture> fields) {
            this.scope = scope; this.objectId = objectId; capturedAt = at; this.source = source;
            this.stats = copyStats(stats); this.fields = Collections.unmodifiableMap(new HashMap<>(fields));
        }
        public Integer value(StatType type) { StatData s = stats.get(type); return s == null ? null : s.statValue; }
        public String text(StatType type) { StatData s = stats.get(type); return s == null ? null : s.stringStatValue; }
        public FieldCapture field(StatType type) { return fields.get(type.get()); }
        public Stat stats() { return copyStats(stats); }
    }
    public static final class Snapshot {
        public final Scope scope;
        public final Quests quests;
        public final List<Pet> pets;
        public final Integer equippedId;
        public final TomatoData.PetAvailability equipped;
        public final long revision;
        private Snapshot(Scope scope, Quests quests, Collection<Pet> pets, Integer equippedId,
                         TomatoData.PetAvailability equipped, long revision) {
            this.scope = scope; this.quests = quests; this.pets = Collections.unmodifiableList(new ArrayList<>(pets));
            this.equippedId = equippedId; this.equipped = equipped; this.revision = revision;
        }
        public boolean currentQuests() { return quests != null && quests.scope == scope && scope.account != null && scope.accepting; }
    }

    private volatile Scope scope = new Scope(0, null, "Waiting for capture identity", true);
    private Quests quests;
    private final Map<String, Pet> pets = new LinkedHashMap<>();
    private final Map<Integer, String> petObjects = new HashMap<>();
    private Integer equippedId;
    private TomatoData.PetAvailability equipped = TomatoData.PetAvailability.UNKNOWN;
    private long revision;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Scope scope() { return scope; }
    public synchronized Snapshot snapshot() { return new Snapshot(scope, quests, pets.values(), equippedId, equipped, revision); }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() { revision++; for (Runnable listener : listeners) listener.run(); }
    public synchronized void reset(String account, String reason) { reset(account, reason, scope.accepting); }
    private void reset(String account, String reason, boolean accepting) {
        scope = new Scope(scope.generation + 1, account, reason, accepting);
        pets.clear(); petObjects.clear(); equippedId = null; equipped = TomatoData.PetAvailability.UNKNOWN; changed();
    }
    /** Thread-safe lifecycle hooks; do not touch capture-thread-owned model collections. */
    public synchronized void captureStopped() { reset(null, "Capture stopped; previous observations are stale", false); }
    public synchronized void captureStarted() { reset(null, "Waiting for a new connection and account", true); }
    public synchronized boolean quests(Scope origin, QuestData[] rows, long at) {
        if (origin != scope || !scope.accepting || rows == null) return false;
        quests = new Quests(origin, at, rows); changed(); return true;
    }
    public synchronized void pet(Scope origin, int objectId, Stat stats, long at, String source,
                                 Map<Integer, FieldCapture> fields) {
        if (origin != scope || !scope.accepting) return;
        StatData id = stats.get(StatType.PET_INSTANCE_ID_STAT);
        String key = id == null ? petObjects.getOrDefault(objectId, "object:" + objectId) : "pet:" + id.statValue;
        // A supplied instance ID promotes this same observed object; unrelated unknown IDs stay separate.
        Pet previous = pets.get(key);
        if (previous == null) previous = pets.get("object:" + objectId);
        if (objectId < 0 && id == null) previous = null; // No observed object/instance identity joins two metadata responses.
        Stat merged = previous == null ? new Stat() : previous.stats();
        Map<Integer, FieldCapture> evidence = previous == null ? new HashMap<>() : new HashMap<>(previous.fields);
        for (StatType type : StatType.values()) if (stats.get(type) != null) {
            FieldCapture incoming = fields.getOrDefault(type.get(), new FieldCapture(at, source));
            FieldCapture retained = evidence.get(type.get());
            if (retained != null && incoming.at < retained.at) continue;
            merged.set(type, stats.get(type));
            evidence.put(type.get(), incoming);
        }
        if (id != null) pets.remove("object:" + objectId);
        if (objectId >= 0) petObjects.put(objectId, key);
        pets.put(key, new Pet(origin, objectId, previous == null ? at : Math.max(at, previous.capturedAt), source, merged, evidence)); changed();
    }
    public synchronized void equipped(Scope origin, TomatoData.PetAvailability availability, Integer id) {
        if (origin != scope || !scope.accepting) return;
        equipped = availability; equippedId = id; changed();
    }
    public synchronized void clearPets() { pets.clear(); petObjects.clear(); equippedId = null; equipped = TomatoData.PetAvailability.UNKNOWN; changed(); }
    public synchronized boolean hasPetObject(int objectId) { return petObjects.containsKey(objectId); }

    public static Stat copyStats(Stat source) {
        Stat result = new Stat();
        for (StatType type : StatType.values()) {
            StatData s = source.get(type); if (s == null) continue;
            StatData c = new StatData(); c.statType = type; c.statTypeNum = type.get();
            c.statValue = s.statValue; c.statValueTwo = s.statValueTwo; c.stringStatValue = s.stringStatValue;
            result.set(type, c);
        }
        return result;
    }
    private static QuestData[] copyQuests(QuestData[] source) {
        List<QuestData> rows = new ArrayList<>();
        if (source != null) for (QuestData s : source) if (s != null) {
            QuestData c = new QuestData(); c.id = s.id; c.name = s.name; c.description = s.description; c.expiration = s.expiration;
            c.category = s.category; c.unknownInt = s.unknownInt; c.completed = s.completed; c.repeatable = s.repeatable; c.itemOfChoice = s.itemOfChoice;
            c.requirements = s.requirements == null ? null : s.requirements.clone(); c.rewards = s.rewards == null ? null : s.rewards.clone(); rows.add(c);
        }
        return rows.toArray(new QuestData[0]);
    }
}
