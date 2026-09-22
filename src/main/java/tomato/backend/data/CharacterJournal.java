package tomato.backend.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.enums.CharacterClass;

/** Local snapshots of the user's characters. Never serializes packets, entities or credentials. */
public final class CharacterJournal implements AutoCloseable {
    public static final String[] STATS = {"Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"};
    public static final int[] EXALT_ORDER = {7, 6, 5, 4, 1, 0, 2, 3};
    private static final StatType[] VALUES = {StatType.MAX_HP_STAT, StatType.MAX_MP_STAT, StatType.ATTACK_STAT,
        StatType.DEFENSE_STAT, StatType.SPEED_STAT, StatType.DEXTERITY_STAT, StatType.VITALITY_STAT, StatType.WISDOM_STAT};
    private static final StatType[] BOOSTS = {StatType.MAX_HP_BOOST_STAT, StatType.MAX_MP_BOOST_STAT, StatType.ATTACK_BOOST_STAT,
        StatType.DEFENSE_BOOST_STAT, StatType.SPEED_BOOST_STAT, StatType.DEXTERITY_BOOST_STAT, StatType.VITALITY_BOOST_STAT, StatType.WISDOM_BOOST_STAT};
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class CharacterRecord {
        public String key, account, name, className, created;
        public int characterId, classId;
        public Integer level, skin;
        public Long fame;
        public Boolean seasonal;
        public long firstSeen, lastSeen, diedAt;
        public long lastObservedAlive, rosterReceivedAt, observedAgainAt;
        public Map<String, FieldCapture> fields = new HashMap<>();
        private transient long observationRevision;
        private transient long rosterRevision;
        public boolean dead;
        public Integer[] stats = new Integer[8];
        public Integer[] equipment = new Integer[28];
        public String notes = "";
        public String source = "Captured character";
    }
    public static final class AccountRecord {
        public String key, name;
        public Map<Integer, int[]> exalts = new TreeMap<>();
        public long exaltSeen;
    }
    private static final class Document {
        int version = 2;
        List<CharacterRecord> characters = new ArrayList<>();
        Map<String, AccountRecord> accounts = new LinkedHashMap<>();
    }
    private final Path path;
    private final Object saveLock = new Object();
    private final Store store;
    private Document document = new Document();
    private boolean dirty, readOnly;
    private long revision;
    private String storageStatus = "Saved locally";
    private ScheduledExecutorService writer;
    private Thread shutdown;
    private final Map<String, CharacterRecord> pendingAlive = new HashMap<>();

    public CharacterJournal(Path path) {
        this(path, CharacterJournal::writeFile);
    }

    @FunctionalInterface
    interface Store { void write(Path path, String json) throws IOException; }

    CharacterJournal(Path path, Store store) {
        this.path = path;
        this.store = store;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Document loaded = JSON.fromJson(reader, Document.class);
                if (loaded == null || (loaded.version != 1 && loaded.version != 2) || loaded.characters == null || loaded.accounts == null)
                    throw new IOException("Unsupported journal");
                for (CharacterRecord r : loaded.characters) {
                    if (r == null || r.key == null || r.account == null || r.stats == null || r.stats.length != 8
                         || r.equipment == null || r.equipment.length != 28) throw new IOException("Invalid character");
                    if (r.fields == null) r.fields = new HashMap<>();
                    for (Map.Entry<String, FieldCapture> field : r.fields.entrySet())
                        if (field.getKey() == null || field.getValue() == null || field.getValue().at < 0 || field.getValue().source == null)
                            throw new IOException("Invalid field provenance");
                    if (loaded.version == 1) r.source = "Legacy snapshot; field provenance unknown";
                }
                for (AccountRecord a : loaded.accounts.values()) {
                    if (a == null || a.key == null || a.exalts == null) throw new IOException("Invalid account");
                    for (int[] values : a.exalts.values()) if (!validExalts(values)) throw new IOException("Invalid exalts");
                }
                loaded.version = 2;
                document = loaded;
            } catch (Exception e) {
                readOnly = true;
                storageStatus = "Cannot read Characters/journal.json. Original preserved; saving disabled.";
            }
        }
    }

    public synchronized void startSaving() {
        if (writer != null) return;
        writer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "character-journal-save"); t.setDaemon(true); return t;
        });
        writer.scheduleWithFixedDelay(this::save, 2, 2, TimeUnit.SECONDS);
        shutdown = new Thread(this::save, "character-journal-exit");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    public synchronized String observe(Entity player, int characterId) {
        if (player == null || characterId < 0) return null;
        StatData accountStat = player.stat.get(StatType.ACCOUNT_ID_STAT);
        if (accountStat == null || accountStat.stringStatValue == null || accountStat.stringStatValue.trim().isEmpty()) return null;
        String account = accountKey(accountStat.stringStatValue);
        String key = account + ":" + characterId;
        CharacterRecord record = find(key);
        if (record == null) {
            record = new CharacterRecord(); record.key = key; record.account = account;
            record.characterId = characterId; record.firstSeen = System.currentTimeMillis();
            document.characters.add(record);
            changed();
        }
        CharacterRecord protectedRecord = record.dead ? record : null;
        if (protectedRecord != null) {
            record = pendingAlive.containsKey(key) ? copy(pendingAlive.get(key)) : copy(record);
            record.dead = false;
        }
        CharacterRecord before = copy(record);
        if (player.typeCapture() != null) {
            record.classId = player.objectType;
            record.className = CharacterClass.getName(record.classId);
            record.fields.put("class", player.typeCapture());
        }
        if (player.getStatName() != null) record.name = player.getStatName().split(",")[0];
        capture(record, "name", player.fieldCapture(StatType.NAME_STAT));
        AccountRecord a = account(account); a.name = record.name;
        record.level = value(player, StatType.LEVEL_STAT, record.level);
        record.skin = value(player, StatType.SKIN_ID, record.skin);
        capture(record, "level", player.fieldCapture(StatType.LEVEL_STAT));
        capture(record, "skin", player.fieldCapture(StatType.SKIN_ID));
        Integer seasonal = value(player, StatType.SEASONAL, null);
        if (seasonal != null && (seasonal == 0 || seasonal == 1)) {
            record.seasonal = seasonal == 1;
            capture(record, "seasonal", player.fieldCapture(StatType.SEASONAL));
        }
        Integer fame = value(player, StatType.CURR_FAME_STAT, null);
        if (fame != null) record.fame = fame.longValue();
        capture(record, "fame", player.fieldCapture(StatType.CURR_FAME_STAT));
        for (int i = 0; i < 8; i++) {
            Integer total = value(player, VALUES[i], null), bonus = value(player, BOOSTS[i], null);
            if (total != null && bonus != null) {
                record.stats[i] = Math.max(0, total - bonus);
                record.fields.put("stat." + i, new FieldCapture(Math.min(player.fieldCapture(VALUES[i]).at,
                    player.fieldCapture(BOOSTS[i]).at), "Captured total minus boost"));
            }
        }
        for (int i = 0; i < 28; i++) {
            int stat = i < 12 ? 8 + i : 131 + i - 12;
            StatData item = player.stat.get(stat);
            if (item != null) { record.equipment[i] = item.statValue; capture(record, "equipment." + i, player.fieldCapture(stat)); }
        }
        boolean newObservation = player.observationRevision() != 0 && player.observationRevision() != record.observationRevision;
        record.observationRevision = player.observationRevision();
        record.lastObservedAlive = Math.max(record.lastObservedAlive, player.observedAt());
        record.lastSeen = Math.max(record.lastSeen, record.lastObservedAlive);
        if (protectedRecord != null) {
            if (newObservation) {
                protectedRecord.observationRevision = record.observationRevision;
                pendingAlive.put(key, record);
                if (protectedRecord.observedAgainAt != player.observedAt()) {
                    protectedRecord.observedAgainAt = player.observedAt(); changed();
                }
            }
        } else if (!sameObservation(before, record) || !sameFields(before.fields, record.fields)
            || before.lastObservedAlive != record.lastObservedAlive) {
            changed();
        }
        return account;
    }

    public synchronized void mergeRoster(String account, List<RealmCharacter> chars) {
        if (account == null || chars == null) return;
        for (RealmCharacter c : chars) {
            String key = account + ":" + c.charId;
            CharacterRecord r = find(key);
            if (r == null) {
                r = new CharacterRecord(); r.key = key; r.account = account; r.characterId = c.charId;
                r.firstSeen = System.currentTimeMillis(); document.characters.add(r);
            }
            CharacterRecord protectedRecord = r.dead ? r : null;
            if (protectedRecord != null) { r = pendingAlive.containsKey(key) ? copy(pendingAlive.get(key)) : copy(r); r.dead = false; }
            if (c.presence.containsKey("class")) { r.classId = c.classNum; r.className = c.classString; }
            if (c.presence.containsKey("level")) r.level = c.level;
            if (c.presence.containsKey("skin")) r.skin = c.skin;
            if (c.presence.containsKey("fame")) r.fame = c.fame;
            if (c.presence.containsKey("seasonal")) r.seasonal = c.seasonal;
            if (c.presence.containsKey("created")) r.created = c.date;
            if (account(account).name != null) r.name = account(account).name;
            r.source = "Character list + capture";
            int[] values = {c.hp, c.mp, c.atk, c.def, c.spd, c.dex, c.vit, c.wis};
            for (int i = 0; i < 8; i++) if ((c.capturedStatMask & (1 << i)) != 0) r.stats[i] = values[i];
            if (c.equipment != null) for (int i = 0; i < Math.min(28, c.equipment.length); i++) {
                r.equipment[i] = c.equipment[i];
                r.fields.put("equipment." + i, c.presence.getOrDefault("equipment." + i, new FieldCapture(0, "Legacy character list")));
            }
            for (Map.Entry<String, FieldCapture> field : c.presence.entrySet())
                if (!field.getKey().startsWith("pet.")) r.fields.put(field.getKey(), field.getValue());
            r.rosterReceivedAt = Math.max(r.rosterReceivedAt, c.receivedAt);
            r.lastSeen = Math.max(r.lastSeen, c.receivedAt);
            boolean newRoster = c.rosterRevision != r.rosterRevision && c.receivedAt > 0;
            r.rosterRevision = c.rosterRevision;
            if (protectedRecord != null && newRoster) {
                protectedRecord.rosterRevision = c.rosterRevision;
                pendingAlive.put(key, r); protectedRecord.observedAgainAt = Math.max(protectedRecord.observedAgainAt, c.receivedAt);
            }
        }
        changed();
    }

    public synchronized void exalts(String account, Map<Integer, int[]> values) {
        if (account == null || values == null) return;
        AccountRecord a = account(account);
        for (Map.Entry<Integer, int[]> entry : values.entrySet()) {
            if (!validExalts(entry.getValue())) continue;
            if (!Arrays.equals(a.exalts.get(entry.getKey()), entry.getValue())) {
                a.exalts.put(entry.getKey(), entry.getValue().clone()); a.exaltSeen = System.currentTimeMillis(); changed();
            }
        }
    }
    private static boolean validExalts(int[] v) { return v != null && v.length == 8 && Arrays.stream(v).allMatch(n -> n >= 0); }
    private AccountRecord account(String key) {
        AccountRecord a = document.accounts.get(key);
        if (a == null) { a = new AccountRecord(); a.key = key; document.accounts.put(key, a); }
        return a;
    }
    private CharacterRecord find(String key) { for (CharacterRecord r : document.characters) if (r.key.equals(key)) return r; return null; }
    private void changed() { dirty = true; revision++; }
    public synchronized long revision() { return revision; }
    public List<CharacterRecord> characters() {
        List<CharacterRecord> copy = new ArrayList<>();
        synchronized (this) {
            for (CharacterRecord r : document.characters) copy.add(copy(r));
        }
        copy.sort(Comparator.comparingLong((CharacterRecord r) -> r.lastSeen).reversed()); return copy;
    }
    public synchronized List<AccountRecord> accounts() {
        List<AccountRecord> copy = new ArrayList<>();
        for (AccountRecord a : document.accounts.values()) copy.add(copy(a));
        return copy;
    }
    public synchronized void markDead(String key, boolean dead) {
        CharacterRecord r = find(key); if (r == null) return;
        if (r.dead == dead) return;
        if (!dead) {
            CharacterRecord next = pendingAlive.remove(key);
            if (next != null) {
                next.notes = r.notes;
                document.characters.set(document.characters.indexOf(r), next);
                r = next;
            }
        } else pendingAlive.remove(key);
        r.observedAgainAt = 0;
        r.dead = dead; r.diedAt = dead ? System.currentTimeMillis() : 0; changed();
    }
    public synchronized void notes(String key, String notes) {
        CharacterRecord r = find(key); if (r == null) return;
        r.notes = notes == null ? "" : notes; changed();
    }
    public synchronized String storageStatus() { return dirty && storageStatus.startsWith("Saved") ? "Saving locally…" : storageStatus; }
    public void save() {
        // Take the snapshot AFTER acquiring the writer lock, so an older caller cannot
        // overwrite a newer save. Readers/observations never acquire this lock.
        synchronized (saveLock) {
            Document snapshot = new Document();
            long savedRevision;
            synchronized (this) {
                if (!dirty || readOnly) return;
                savedRevision = revision;
                for (CharacterRecord r : document.characters) snapshot.characters.add(copy(r));
                document.accounts.forEach((key, value) -> snapshot.accounts.put(key, copy(value)));
            }
            try {
                store.write(path, JSON.toJson(snapshot));
                synchronized (this) {
                    if (revision == savedRevision) dirty = false;
                    storageStatus = "Saved locally • Characters/journal.json";
                }
            } catch (IOException e) {
                synchronized (this) { storageStatus = "Save failed • check access to Characters/journal.json"; }
            }
        }
    }

    private static void writeFile(Path path, String json) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (Writer out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { out.write(json); }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() {
        Thread hook;
        synchronized (this) {
            if (writer != null) writer.shutdown();
            hook = shutdown; shutdown = null;
        }
        save();
        if (hook != null) { try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) {} }
    }

    private static CharacterRecord copy(CharacterRecord r) {
        CharacterRecord c = new CharacterRecord();
        c.key = r.key; c.account = r.account; c.name = r.name; c.className = r.className; c.created = r.created;
        c.characterId = r.characterId; c.classId = r.classId; c.level = r.level; c.skin = r.skin;
        c.fame = r.fame; c.seasonal = r.seasonal; c.firstSeen = r.firstSeen; c.lastSeen = r.lastSeen;
        c.diedAt = r.diedAt; c.dead = r.dead; c.notes = r.notes; c.source = r.source;
        c.lastObservedAlive = r.lastObservedAlive; c.rosterReceivedAt = r.rosterReceivedAt; c.observedAgainAt = r.observedAgainAt;
        c.fields = new HashMap<>(r.fields);
        c.observationRevision = r.observationRevision;
        c.rosterRevision = r.rosterRevision;
        c.stats = r.stats.clone(); c.equipment = r.equipment.clone();
        return c;
    }

    private static AccountRecord copy(AccountRecord a) {
        AccountRecord c = new AccountRecord(); c.key = a.key; c.name = a.name; c.exaltSeen = a.exaltSeen;
        a.exalts.forEach((id, values) -> c.exalts.put(id, values.clone()));
        return c;
    }

    private static boolean sameObservation(CharacterRecord a, CharacterRecord b) {
        return a.classId == b.classId && Objects.equals(a.className, b.className) && Objects.equals(a.name, b.name)
            && Objects.equals(a.level, b.level) && Objects.equals(a.skin, b.skin) && Objects.equals(a.fame, b.fame)
            && Objects.equals(a.seasonal, b.seasonal) && Arrays.equals(a.stats, b.stats) && Arrays.equals(a.equipment, b.equipment);
    }
    private static void capture(CharacterRecord record, String field, FieldCapture value) {
        if (value != null) record.fields.put(field, value);
    }
    private static boolean sameFields(Map<String, FieldCapture> a, Map<String, FieldCapture> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String field : a.keySet()) {
            if (a.get(field).at != b.get(field).at || !Objects.equals(a.get(field).source, b.get(field).source)) return false;
        }
        return true;
    }
    public static int maxed(CharacterRecord r, int[] caps) {
        if (caps == null || caps.length != 8) return -1;
        int count = 0;
        for (int i = 0; i < 8; i++) { if (r.stats[i] == null) return -1; if (r.stats[i] >= caps[i]) count++; }
        return count;
    }
    public static int potions(Integer value, int cap, int index) { return value == null ? -1 : (int)Math.ceil(Math.max(0, cap - value) / (index < 2 ? 5d : 1d)); }
    public static int exaltLevel(int count) { int level = 0; for (int goal : new int[]{5, 15, 30, 50, 75}) if (count >= goal) level++; return level; }
    private static Integer value(Entity e, StatType type, Integer fallback) {
        StatData s = e.stat.get(type);
        if (s == null) return fallback;
        return s.statValue;
    }
    public static String accountKey(String id) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
