package tomato.backend.data;

import assets.IdToAsset;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.gui.stats.DungeonStats;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.Map;
import java.util.TreeMap;

public class DungeonStatData {

    private static final String FILE_NAME = "dungeon.stats";
    private static final Gson JSON = new Gson();
    private final transient Object saveLock = new Object();
    private final transient Path path;
    private final transient Store store;
    private final transient Loader loader;
    // Guarded by saveLock: disk history is incorporated exactly once, before any write.
    private transient boolean historyLoaded;
    private transient boolean historyLoadFailed;

    public TreeMap<String, DungeonInfo> data;
    private final transient TreeMap<String, DungeonInfo> sessionData = new TreeMap<>();
    public DungeonInfo info;

    public DungeonStatData() {
        this(Paths.get(FILE_NAME));
    }

    public DungeonStatData(Path path) { this(path, DungeonStatData::writeFile); }

    @FunctionalInterface
    interface Store { void write(Path path, String json) throws IOException; }
    @FunctionalInterface
    interface Loader { Reader open(Path path) throws IOException; }

    DungeonStatData(Path path, Store store) {
        this(path, store, file -> Files.newBufferedReader(file, Charset.defaultCharset()));
    }

    DungeonStatData(Path path, Store store, Loader loader) {
        this.path = path; this.store = store; this.loader = loader;
        data = new TreeMap<>();
        tomato.history.SessionStore history = tomato.history.AppHistory.store();
        if (history != null && history.writable()) history.collect("dungeon-totals", () -> history.put("dungeon-totals", "summary", sessionSnapshot()));
    }

    public synchronized void updateEntityDamage(String dungeon, Entity mob) {
        if (mob == null || mob.objectType == 0) return;
        if (info == null) {
            info = data.computeIfAbsent(dungeon, d -> new DungeonInfo(dungeon));
        } else if (!info.name.equals(dungeon)) {
            System.out.println("dungeon mismatch entity damage");
            return;
        }
        int entityType = mob.objectType;
        info.addMob(entityType);
        sessionData.computeIfAbsent(dungeon, DungeonInfo::new).addMob(entityType);

        DungeonStats.update(this, dungeon);
    }

    public synchronized void updateItems(String dungeon, Entity mob, Entity items) {
        if (items == null) return;
        if (info == null) {
            info = data.computeIfAbsent(dungeon, d -> new DungeonInfo(dungeon));
        } else if (!info.name.equals(dungeon)) {
            System.out.println("dungeon mismatch items");
            return;
        }
        int entityType = mob != null ? mob.objectType : 0;

//        StatData udata = items.stat.get(StatType.UNIQUE_DATA_STRING);
//        String[] enchants = null;
//        if (udata != null && udata.stringStatValue != null) {
//            enchants = udata.stringStatValue.split(",");
//        }
        for (int i = 0; i < 8; i++) {
            StatData sd = items.stat.get(StatType.INVENTORY_0_STAT.get() + i);
            if (sd == null || sd.statValue < 1) continue;
//            if (enchants != null && i < enchants.length && !enchants[i].isEmpty() && !enchants[i].equals("AAIE_f_9__3__f8=")) {
//            }

            int itemId = sd.statValue;

            info.addItems(entityType, itemId);
            sessionData.computeIfAbsent(dungeon, DungeonInfo::new).addItems(entityType, itemId);
        }
        DungeonStats.update(this, dungeon);
    }

    public void updateDungeon(String dungeon, long time) {
        synchronized (this) {
            if (info == null || !info.name.equals(dungeon)) return;
            info.totalTime += time;
            info.enteredDungeon++;
            DungeonInfo session = sessionData.computeIfAbsent(dungeon, DungeonInfo::new);
            session.totalTime += Math.max(0, time); session.enteredDungeon++;
            DungeonStats.update(this, dungeon);
            info = null;
        }
        // Preserve the synchronous save contract, without holding up snapshot readers.
        save();
    }

    private void save() {
        synchronized (saveLock) {
            if (historyLoadFailed) return;
            loadHistory();
            Document copy;
            synchronized (this) { copy = copyDocument(); }
            try { store.write(path, JSON.toJson(copy)); }
            catch (IOException e) { throw new RuntimeException(e); }
        }
    }

    public void load() {
        synchronized (saveLock) {
            try {
                loadHistory();
                historyLoadFailed = false;
            } catch (RuntimeException failure) {
                historyLoadFailed = true;
                throw failure;
            }
        }
        DungeonStats.update(this, null);
    }

    private void loadHistory() {
        if (historyLoaded) return;
        Document history;
        try (Reader input = loader.open(path); JsonReader reader = new JsonReader(input)) {
            history = JSON.fromJson(reader, Document.class);
            if (history == null || history.data == null) throw new IOException("Invalid dungeon history");
            // Validate the whole baseline before merging any of it, so a failed load
            // can be retried without adding part of the saved counters twice.
            for (DungeonInfo saved : history.data.values()) {
                if (saved == null || saved.name == null) throw new IOException("Invalid dungeon entry");
                if (saved.entityDamaged != null && saved.entityDamaged.containsValue(null))
                    throw new IOException("Invalid dungeon hit count");
                if (saved.entityLoot != null) for (Loot loot : saved.entityLoot.values())
                    if (loot != null && loot.lootList != null && loot.lootList.containsValue(null))
                        throw new IOException("Invalid dungeon loot count");
            }
        } catch (NoSuchFileException e) {
            historyLoaded = true;
            return;
        } catch (IOException e) { throw new RuntimeException(e); }
        synchronized (this) {
            // Before initialization, data contains only this run's observations. Merge
            // the saved baseline into those deltas, keeping active DungeonInfo references.
            history.data.forEach((name, saved) -> {
                DungeonInfo current = data.computeIfAbsent(name, key -> new DungeonInfo(saved.name));
                current.enteredDungeon += saved.enteredDungeon;
                current.totalTime += saved.totalTime;
                if (saved.entityDamaged != null) saved.entityDamaged.forEach((id, count) ->
                    current.entityDamaged.merge(id, count, Integer::sum));
                if (saved.entityLoot != null) saved.entityLoot.forEach((id, loot) -> {
                    if (loot != null && loot.lootList != null) {
                        Loot target = current.entityLoot.computeIfAbsent(id, key -> new Loot());
                        loot.lootList.forEach((item, count) -> target.lootList.merge(item, count, Integer::sum));
                    }
                });
            });
            historyLoaded = true;
        }
    }

    private static final class Document {
        TreeMap<String, DungeonInfo> data = new TreeMap<>();
        DungeonInfo info;
    }

    private Document copyDocument() {
        Document copy = new Document();
        data.forEach((name, value) -> copy.data.put(name, copy(value)));
        if (info != null) copy.info = copy(info);
        return copy;
    }

    private DungeonInfo copy(DungeonInfo source) {
        DungeonInfo copy = new DungeonInfo(source.name);
        copy.enteredDungeon = source.enteredDungeon; copy.totalTime = source.totalTime;
        if (source.entityDamaged != null) copy.entityDamaged.putAll(source.entityDamaged);
        if (source.entityLoot != null) source.entityLoot.forEach((id, sourceLoot) -> {
            Loot loot = new Loot();
            if (sourceLoot != null && sourceLoot.lootList != null) loot.lootList.putAll(sourceLoot.lootList);
            copy.entityLoot.put(id, loot);
        });
        return copy;
    }

    private static void writeFile(Path path, String json) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.write(temporary, json.getBytes(Charset.defaultCharset()));
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Copies cumulative counters under the capture lock for safe UI reads. */
    public synchronized java.util.List<Snapshot> snapshot() {
        return snapshot(data);
    }
    public synchronized java.util.List<Snapshot> sessionSnapshot() { return snapshot(sessionData); }
    private java.util.List<Snapshot> snapshot(TreeMap<String, DungeonInfo> data) {
        java.util.List<Snapshot> result = new java.util.ArrayList<>();
        if (data != null) for (DungeonInfo dungeon : data.values()) {
            Snapshot copy = new Snapshot(dungeon.name, dungeon.enteredDungeon, dungeon.totalTime);
            if (dungeon.entityDamaged != null) copy.hits.putAll(dungeon.entityDamaged);
            if (dungeon.entityLoot != null) dungeon.entityLoot.forEach((id, loot) -> {
                if (loot != null && loot.lootList != null) copy.loot.put(id, new TreeMap<>(loot.lootList));
            });
            result.add(copy);
        }
        return result;
    }

    public static final class Snapshot {
        public final String name;
        public final int visits;
        public final long time;
        public final Map<Integer, Integer> hits = new TreeMap<>();
        public final Map<Integer, Map<Integer, Integer>> loot = new TreeMap<>();
        public Snapshot(String name, int visits, long time) { this.name = name; this.visits = visits; this.time = time; }
        public long hitCount() { return hits.values().stream().mapToLong(Integer::longValue).sum(); }
        public long itemCount() { return loot.values().stream().flatMap(m -> m.values().stream()).mapToLong(Integer::longValue).sum(); }
    }

    public class DungeonInfo {
        private String name;
        private int enteredDungeon;
        private long totalTime;
        private TreeMap<Integer, Integer> entityDamaged = new TreeMap<>();
        private TreeMap<Integer, Loot> entityLoot = new TreeMap<>();

        public DungeonInfo(String name) {
            this.name = name;
        }

        public void addMob(int entity) {
            entityDamaged.merge(entity, 1, Integer::sum);
        }

        public void addItems(int entity, int itemId) {
            entityLoot.computeIfAbsent(entity, i -> new Loot()).add(itemId);
        }

        public int getEnteredDungeon() {
            return enteredDungeon;
        }

        public long getTotalTime() {
            return totalTime;
        }

        public String getName() {
            return name;
        }

        public Map<Integer, Integer> getEntityDamaged() {
            return entityDamaged;
        }

        public Loot getLoot(int id) {
            return entityLoot.get(id);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append(" [").append(enteredDungeon).append("] ").append(totalTime).append("\n");
            for (Map.Entry<Integer, Integer> e : entityDamaged.entrySet()) {
                Integer k = e.getKey();
                sb.append("  ").append(IdToAsset.objectName(k)).append(":").append(e.getValue()).append("\n");
                Loot loot = entityLoot.get(k);
                if (loot != null) sb.append(loot);
            }

            Loot lootUnknown = entityLoot.get(0);
            if (lootUnknown != null) {
                sb.append("  ").append("Unknown").append("\n");
                sb.append(lootUnknown);
            }

            return sb.toString();
        }
    }

    public class Loot {
        private TreeMap<Integer, Integer> lootList = new TreeMap<>();

        public void add(int itemId) {
            lootList.merge(itemId, 1, Integer::sum);
        }

        public TreeMap<Integer, Integer> getItems() {
            return lootList;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, Integer> e : lootList.entrySet()) {
                sb.append("    ").append(IdToAsset.objectName(e.getKey())).append(":").append(e.getValue()).append("\n");
            }
            return sb.toString();
        }

    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, DungeonInfo> e : data.entrySet()) {
            sb.append(e.getValue());
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        DungeonStatData data = new DungeonStatData();

        data.load();
        System.out.println(data);
        if (true) return;

        Entity e = new Entity(null, 0, 0);
        e.objectType = 100;
        Entity e2 = new Entity(null, 0, 0);
        e2.objectType = 102;
        data.updateEntityDamage("Temp Dung", e);
        data.updateEntityDamage("Temp Dung", e);
        data.updateEntityDamage("Temp Dung", e2);

        Entity items = new Entity(null, 0, 0);
        StatData d = new StatData();
        d.statValue = 1;
        items.stat.set(StatType.INVENTORY_0_STAT, d);
        StatData d2 = new StatData();
        d2.statValue = 2;
        items.stat.set(StatType.INVENTORY_1_STAT, d2);
        data.updateItems("Temp Dung", e, items);
        data.updateItems("Temp Dung", null, items);

        data.updateDungeon("Temp Dung", 1000);
        Entity e3 = new Entity(null, 0, 0);
        e3.objectType = 100;
        data.updateEntityDamage("Temp Dung 2", e3);
        data.updateDungeon("Temp Dung 2", 1234);

//        System.out.println(data);

//        String json = gson.toJson(data);
//        System.out.println(json);
        data.save();

//        Gson gson = new Gson();
//        data = gson.fromJson(json, DungeonStatData.class);
//        System.out.println(data);
    }
}
