package tomato.backend.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.RealmCharacterStats;
import tomato.realmshark.enums.CharacterClass;
import tomato.realmshark.enums.CharacterStatistics;

/** Local snapshots of the user's characters. Never serializes packets, entities or credentials. */
public final class CharacterJournal implements AutoCloseable {
    public static final String[] STATS = {"Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"};
    public static final int[] EXALT_ORDER = {7, 6, 5, 4, 1, 0, 2, 3};
    private static final StatType[] VALUES = {StatType.MAX_HP_STAT, StatType.MAX_MP_STAT, StatType.ATTACK_STAT,
        StatType.DEFENSE_STAT, StatType.SPEED_STAT, StatType.DEXTERITY_STAT, StatType.VITALITY_STAT, StatType.WISDOM_STAT};
    private static final StatType[] BOOSTS = {StatType.MAX_HP_BOOST_STAT, StatType.MAX_MP_BOOST_STAT, StatType.ATTACK_BOOST_STAT,
        StatType.DEFENSE_BOOST_STAT, StatType.SPEED_BOOST_STAT, StatType.DEXTERITY_BOOST_STAT, StatType.VITALITY_BOOST_STAT, StatType.WISDOM_BOOST_STAT};
    /** Live exaltation bonus stats in canonical order: life, mana, atk, def, spd, dex, vit, wis (not StatType id order). */
    private static final StatType[] EXALT_BONUSES = {StatType.EXALTED_HP, StatType.EXALTED_MP, StatType.EXALTED_ATK,
        StatType.EXALTED_DEF, StatType.EXALTED_SPD, StatType.EXALTED_DEX, StatType.EXALTED_VIT, StatType.EXALTED_WIS};
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
        /**
         * v5: the four equipped items' UNIQUE_DATA_STRING entries (weapon, ability, armor, ring) as last observed live; null = not
         * recorded (older records, or never observed since); a null element is a slot the capture did not include.
         */
        public String[] equipmentEnchants;

        /** The equipped items' enchantments for display; null when none were recorded. */
        public List<EnchantInfo> enchantInfos() {
            if (equipmentEnchants == null) return null;
            List<EnchantInfo> slots = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) slots.add(EnchantInfo.of(i < equipmentEnchants.length ? equipmentEnchants[i] : null));
            return List.copyOf(slots);
        }
        public String notes = "";
        public String source = "Captured character";
        public DeathAnnotation deathAnnotation;
        /** v5: the pet as the character list last reported it (absent = TRUE: no pet), refreshed (with its family) by the Pet Yard; null = unknown. */
        public PetRecord pet;
        /** v5: dungeon name → completions from a complete PCStats decode, positive counts only. Null = unknown; absent in a map = 0. */
        public Map<String, Integer> dungeonCompletions;
        public long dungeonCompletionsObservedAt;
        /** v5: from the character list; null = unknown. */
        public Long exp;
        public Boolean hasBackpack;
    }
    /**
     * v5: a character's pet. A null value was not reported; ability arrays are in slot order with -1 = unknown. {@code absent}
     * TRUE means the character list said the character has no pet (an explicitly empty element): a known "No pet", with every
     * other value unknown. A character whose record has no PetRecord at all is unknown, never "No pet".
     */
    public static final class PetRecord {
        public Boolean absent;
        public Long instanceId;
        public String name;
        public Integer type, rarity, family, skin, maxAbilityPower;
        public int[] abilityType = {-1, -1, -1}, abilityLevel = {-1, -1, -1}, abilityPoints = {-1, -1, -1};
        public long observedAt;
        public String source;
    }
    public static final class DeathAnnotation {
        public Long occurredAt;
        public long markedAt, editedAt;
        public String notes = "";
        public tomato.history.link.VisitRef visit;
    }
    public static final class AccountRecord {
        public String key, name;
        public Map<Integer, int[]> exalts = new TreeMap<>();
        public long exaltSeen;
        /** Class id → live EXALTED_* stat bonuses (canonical stat order) last seen on a character of that class. */
        public Map<Integer, ExaltBonus> liveExaltBonus = new TreeMap<>();
        /** Account-wide live stats; null = never observed. */
        public Long accountFame, gold;
        public Integer rankStars;
        /** Latest receipt time of an account-wide live stat (epoch ms); 0 = never. */
        public long accountStatsObservedAt;
        /** v5: class id → when that class's exalt counts last changed in this journal (epoch ms). */
        public Map<Integer, Long> exaltSeenByClass = new TreeMap<>();
        /** v5: stat potions in the regular vault chest, potion storage and gift chest, canonical order, greater = 2; null = unknown. */
        public int[] vaultPotions;
        public long vaultPotionsObservedAt;
    }
    public static final class ExaltBonus {
        public int[] bonus = new int[8];
        public long observedAt;
    }
    private static final class Document {
        int version = 5;
        List<CharacterRecord> characters = new ArrayList<>();
        Map<String, AccountRecord> accounts = new LinkedHashMap<>();
    }
    private final Path path;
    private final Object saveLock = new Object();
    private final Store store;
    private Document document = new Document();
    private boolean dirty, readOnly;
    /** The last save attempt failed (backup or write); cleared by the next successful write. Never inferred from the text. */
    private volatile boolean saveFailed;
    private long revision;
    private String storageStatus = "Saved locally";
    private ScheduledExecutorService writer;
    private Thread shutdown;
    private final Map<String, CharacterRecord> pendingAlive = new HashMap<>();
    /** A loaded version 1-4 file not yet backed up: the first save copies it once to <name>.v4.bak (saver thread, saveLock held). */
    private volatile boolean backupPending;

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
                JsonElement tree = JsonParser.parseReader(reader);
                dropMalformedV5Fields(tree);
                Document loaded = JSON.fromJson(tree, Document.class);
                if (loaded == null || (loaded.version < 1 || loaded.version > 5) || loaded.characters == null || loaded.accounts == null)
                    throw new IOException("Unsupported journal");
                for (CharacterRecord r : loaded.characters) {
                    if (r == null || r.key == null || r.account == null || r.stats == null || r.stats.length != 8
                         || r.equipment == null || r.equipment.length != 28) throw new IOException("Invalid character");
                    if (r.fields == null) r.fields = new HashMap<>();
                    for (Map.Entry<String, FieldCapture> field : r.fields.entrySet())
                        if (field.getKey() == null || field.getValue() == null || field.getValue().at < 0 || field.getValue().source == null)
                            throw new IOException("Invalid field provenance");
                    if (loaded.version == 1) r.source = "Legacy snapshot; field provenance unknown";
                    if (r.deathAnnotation == null && r.diedAt > 0) {
                        r.deathAnnotation = new DeathAnnotation(); r.deathAnnotation.markedAt = r.diedAt;
                    }
                    if (r.deathAnnotation != null) validateAnnotation(r.deathAnnotation);
                    normalizeV5(r);
                }
                for (AccountRecord a : loaded.accounts.values()) {
                    if (a == null || a.key == null || a.exalts == null) throw new IOException("Invalid account");
                    for (int[] values : a.exalts.values()) if (!validExalts(values)) throw new IOException("Invalid exalts");
                    if (a.liveExaltBonus == null) a.liveExaltBonus = new TreeMap<>(); // v1–v3 documents have no live fields.
                    for (Map.Entry<Integer, ExaltBonus> bonus : a.liveExaltBonus.entrySet())
                        if (bonus.getKey() == null || bonus.getValue() == null || !validExalts(bonus.getValue().bonus) || bonus.getValue().observedAt < 0)
                            throw new IOException("Invalid live exalt bonus");
                    if (a.accountStatsObservedAt < 0) throw new IOException("Invalid account observation time");
                    normalizeV5(a);
                }
                backupPending = loaded.version < 5; // a pre-version-5 file is copied once before version 5 first replaces it
                loaded.version = 5;
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
        String account = accountKeyOf(player);
        if (account == null) return null;
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
            if (item != null) {
                Integer previous = record.equipment[i]; record.equipment[i] = item.statValue; forgetEnchant(record, i, previous, record.equipment[i]);
                capture(record, "equipment." + i, player.fieldCapture(stat));
            }
        }
        StatData enchants = player.stat.get(StatType.UNIQUE_DATA_STRING);
        // Like equipment, an observation without the stat keeps what was saved.
        if (enchants != null && enchants.stringStatValue != null) record.equipmentEnchants = equippedEnchants(enchants.stringStatValue);
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

    /** A changed equipped item invalidates its saved enchantments; a fresh enchant stat in the same observation replaces them anyway. */
    private static void forgetEnchant(CharacterRecord r, int slot, Integer before, Integer after) {
        if (slot < 4 && r.equipmentEnchants != null && slot < r.equipmentEnchants.length && !Objects.equals(before, after)) r.equipmentEnchants[slot] = null;
    }

    /** The first four comma-separated entries (the equipped slots); "" is the protocol's known-empty shorthand for all four. */
    static String[] equippedEnchants(String captured) {
        String[] parts = captured.isEmpty() ? new String[] {"", "", "", ""} : captured.split(",", -1);
        String[] slots = new String[4];
        for (int i = 0; i < 4; i++) slots[i] = i < parts.length ? parts[i] : null;
        return slots;
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
            if (c.presence.containsKey("exp")) r.exp = c.exp;
            if (c.presence.containsKey("backpack")) r.hasBackpack = c.backpack;
            // A delayed list never replaces newer completions (CREATE's PCStats) or a newer pet (the Pet Yard). The list's own
            // PCStats string is decoded again: capture overlays the decoded stats of the character in game with CREATE's.
            if (c.presence.containsKey("dungeons") && c.receivedAt >= r.dungeonCompletionsObservedAt) {
                Map<String, Integer> completions = completions(listedCompletions(c.pcStats));
                if (completions != null) { r.dungeonCompletions = completions; r.dungeonCompletionsObservedAt = c.receivedAt; }
            }
            PetRecord pet = rosterPet(c, r.pet);
            if (pet != null) r.pet = pet;
            if (account(account).name != null) r.name = account(account).name;
            r.source = "Character list + capture";
            int[] values = {c.hp, c.mp, c.atk, c.def, c.spd, c.dex, c.vit, c.wis};
            for (int i = 0; i < 8; i++) if ((c.capturedStatMask & (1 << i)) != 0) r.stats[i] = values[i];
            if (c.equipment != null) for (int i = 0; i < Math.min(28, c.equipment.length); i++) {
                Integer previous = r.equipment[i]; r.equipment[i] = c.equipment[i]; forgetEnchant(r, i, previous, r.equipment[i]);
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
                long now = System.currentTimeMillis();
                a.exalts.put(entry.getKey(), entry.getValue().clone()); a.exaltSeen = now; a.exaltSeenByClass.put(entry.getKey(), now); changed();
            }
        }
    }
    /**
     * Account-wide live stats and this class's live exaltation bonuses (canonical stat order). A null argument was not
     * observed and keeps the previous value; the journal changes only when a value or its observation time does.
     */
    public synchronized void accountLive(String accountKey, int classId, Integer rankStars, Long gold, Long accountFame, int[] exaltBonus, long observedAt) {
        if (accountKey == null) return;
        AccountRecord a = account(accountKey);
        boolean change = false;
        if (rankStars != null && !rankStars.equals(a.rankStars)) { a.rankStars = rankStars; change = true; }
        if (gold != null && !gold.equals(a.gold)) { a.gold = gold; change = true; }
        if (accountFame != null && !accountFame.equals(a.accountFame)) { a.accountFame = accountFame; change = true; }
        if ((rankStars != null || gold != null || accountFame != null) && observedAt > a.accountStatsObservedAt) {
            a.accountStatsObservedAt = observedAt; change = true;
        }
        if (classId > 0 && validExalts(exaltBonus)) {
            ExaltBonus known = a.liveExaltBonus.get(classId);
            if (known == null || !Arrays.equals(known.bonus, exaltBonus) || observedAt > known.observedAt) {
                ExaltBonus next = new ExaltBonus(); next.bonus = exaltBonus.clone();
                next.observedAt = Math.max(observedAt, known == null ? 0 : known.observedAt);
                a.liveExaltBonus.put(classId, next); change = true;
            }
        }
        if (change) changed();
    }
    /** A complete PCStats decode of one character: counts in CharacterStatistics.DUNGEON_NAMES order. Anything else is ignored. */
    public synchronized void dungeonCompletions(String accountKey, int characterId, int[] counts, long observedAt) {
        CharacterRecord r = observable(accountKey, characterId);
        Map<String, Integer> next = completions(counts);
        if (r == null || next == null || observedAt < r.dungeonCompletionsObservedAt) return;
        if (next.equals(r.dungeonCompletions) && observedAt == r.dungeonCompletionsObservedAt) return;
        r.dungeonCompletions = next; r.dungeonCompletionsObservedAt = observedAt; changed();
    }
    /** The regular vault's stat potions (8 entries, canonical order, normal-potion equivalents) seen at {@code observedAt}. */
    public synchronized void vaultPotions(String accountKey, int[] potions, long observedAt) {
        if (accountKey == null || potions == null || potions.length != 8 || Arrays.stream(potions).anyMatch(n -> n < 0)) return;
        AccountRecord a = account(accountKey);
        if (observedAt < a.vaultPotionsObservedAt || Arrays.equals(a.vaultPotions, potions) && observedAt == a.vaultPotionsObservedAt) return;
        a.vaultPotions = potions.clone(); a.vaultPotionsObservedAt = observedAt; changed();
    }
    /**
     * A Pet Yard pet: each of the account's characters whose pet has this instance id takes its family and other reported values
     * (null values and -1 abilities keep what is known). An observation older than the known pet is ignored, and values seen again
     * unchanged do not dirty the journal.
     */
    public synchronized void yardPet(String accountKey, PetRecord seen) {
        if (accountKey == null || seen == null || seen.instanceId == null || !validPet(seen)) return;
        for (CharacterRecord record : document.characters) {
            if (!accountKey.equals(record.account)) continue;
            CharacterRecord r = record.dead ? pendingAlive.get(record.key) : record;
            if (r == null || r.pet == null || !seen.instanceId.equals(r.pet.instanceId) || seen.observedAt < r.pet.observedAt) continue;
            PetRecord next = copy(r.pet);
            if (seen.name != null) next.name = seen.name;
            if (seen.type != null) next.type = seen.type;
            if (seen.rarity != null) next.rarity = seen.rarity;
            if (seen.family != null) next.family = seen.family;
            if (seen.skin != null) next.skin = seen.skin;
            if (seen.maxAbilityPower != null) next.maxAbilityPower = seen.maxAbilityPower;
            for (int i = 0; i < 3; i++) {
                if (seen.abilityType[i] >= 0) next.abilityType[i] = seen.abilityType[i];
                if (seen.abilityLevel[i] >= 0) next.abilityLevel[i] = seen.abilityLevel[i];
                if (seen.abilityPoints[i] >= 0) next.abilityPoints[i] = seen.abilityPoints[i];
            }
            if (samePetValues(next, r.pet)) continue;
            next.observedAt = Math.max(next.observedAt, seen.observedAt);
            next.source = seen.source == null ? "Pet Yard capture" : seen.source;
            r.pet = next; changed();
        }
    }
    /** Where new observations of a character go: its record, or while it is marked dead its pending alive copy (null if none). */
    private CharacterRecord observable(String accountKey, int characterId) {
        if (accountKey == null) return null;
        String key = accountKey + ":" + characterId;
        CharacterRecord r = find(key);
        return r == null || !r.dead ? r : pendingAlive.get(key);
    }
    /** Dungeon name → count (positive counts only) from one count per CharacterStatistics.DUNGEON_NAMES entry; else null. */
    private static Map<String, Integer> completions(int[] counts) {
        List<String> names = CharacterStatistics.DUNGEON_NAMES;
        if (counts == null || counts.length != names.size()) return null;
        Map<String, Integer> result = new TreeMap<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] < 0) return null;
            if (counts[i] > 0) result.put(names.get(i), counts[i]);
        }
        return result;
    }
    /** A complete decode of a character-list PCStats string: counts in CharacterStatistics.DUNGEON_NAMES order, else null. */
    private static int[] listedCompletions(String pcStats) {
        if (pcStats == null) return null;
        try { RealmCharacterStats stats = new RealmCharacterStats(); stats.decode(pcStats); return stats.completionCounts(); }
        catch (RuntimeException malformed) { return null; }
    }
    /**
     * The list's pet, or null when it reported none or is older than the known pet. The same pet (a reported instance id equal to
     * the known one) keeps every value the list omits, including its Pet Yard family; any other pet starts from unknown.
     */
    private static PetRecord rosterPet(RealmCharacter c, PetRecord known) {
        boolean reported = false;
        for (String field : c.presence.keySet()) if (field.startsWith("pet.")) { reported = true; break; }
        if (!reported || known != null && c.receivedAt < known.observedAt) return null;
        // An explicitly empty pet element: no pet is equipped (known), unlike a list that says nothing about pets (unknown).
        if (c.presence.containsKey(RealmCharacter.PET_NONE)) {
            PetRecord none = new PetRecord(); none.absent = Boolean.TRUE; none.observedAt = c.receivedAt; none.source = "Character list"; return none;
        }
        Long instanceId = c.presence.containsKey("pet.81") ? Long.valueOf(c.petInstanceId) : null;
        PetRecord pet = instanceId != null && known != null && instanceId.equals(known.instanceId) ? copy(known) : new PetRecord();
        pet.instanceId = instanceId;
        if (c.presence.containsKey("pet.82")) pet.name = c.petName;
        if (c.presence.containsKey("pet.83")) pet.type = c.petType;
        if (c.presence.containsKey("pet.84")) pet.rarity = c.petRarity;
        if (c.presence.containsKey("pet.85")) pet.maxAbilityPower = c.petMaxAbilityPower;
        if (c.presence.containsKey("pet." + StatType.SKIN_ID.get())) pet.skin = c.petSkin;
        if (c.petAbilitys != null && c.petAbilitys.length == 9) for (int i = 0; i < 3; i++) {
            if (c.presence.containsKey("pet." + (87 + i))) pet.abilityPoints[i] = c.petAbilitys[i * 3];
            if (c.presence.containsKey("pet." + (90 + i))) pet.abilityLevel[i] = c.petAbilitys[i * 3 + 1];
            if (c.presence.containsKey("pet." + (93 + i))) pet.abilityType[i] = c.petAbilitys[i * 3 + 2];
        }
        pet.observedAt = c.receivedAt; pet.source = "Character list";
        return pet;
    }
    private static boolean samePetValues(PetRecord a, PetRecord b) {
        return Objects.equals(a.absent, b.absent) && Objects.equals(a.instanceId, b.instanceId) && Objects.equals(a.name, b.name) && Objects.equals(a.type, b.type)
            && Objects.equals(a.rarity, b.rarity) && Objects.equals(a.family, b.family) && Objects.equals(a.skin, b.skin)
            && Objects.equals(a.maxAbilityPower, b.maxAbilityPower) && Arrays.equals(a.abilityType, b.abilityType)
            && Arrays.equals(a.abilityLevel, b.abilityLevel) && Arrays.equals(a.abilityPoints, b.abilityPoints);
    }
    private static boolean validPet(PetRecord p) {
        for (int[] values : new int[][]{p.abilityType, p.abilityLevel, p.abilityPoints})
            if (values == null || values.length != 3 || Arrays.stream(values).anyMatch(n -> n < -1)) return false;
        // "No pet" carries no pet values: a record that says both is contradictory, so it reads as unknown.
        if (Boolean.TRUE.equals(p.absent) && (p.instanceId != null || p.name != null || p.type != null || p.rarity != null || p.family != null
                || p.skin != null || p.maxAbilityPower != null || known(p.abilityType) || known(p.abilityLevel) || known(p.abilityPoints))) return false;
        return p.observedAt >= 0;
    }
    /** Any ability value other than -1 (unknown) is a pet value. The arrays are already checked non-null with length 3. */
    private static boolean known(int[] values) { return Arrays.stream(values).anyMatch(n -> n != -1); }
    /** v5 values that parsed but make no sense are unknown, never a load failure. */
    private static void normalizeV5(CharacterRecord r) {
        if (r.equipmentEnchants != null && r.equipmentEnchants.length != 4) r.equipmentEnchants = null;
        if (r.pet != null && Boolean.FALSE.equals(r.pet.absent)) r.pet.absent = null; // only TRUE is saved
        if (r.pet != null && !validPet(r.pet)) r.pet = null;
        if (r.dungeonCompletions != null) for (Map.Entry<String, Integer> entry : r.dungeonCompletions.entrySet())
            if (!CharacterStatistics.DUNGEON_NAMES.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0) { r.dungeonCompletions = null; break; }
        if (r.dungeonCompletions == null || r.dungeonCompletionsObservedAt < 0) r.dungeonCompletionsObservedAt = 0;
        if (r.exp != null && r.exp < 0) r.exp = null;
    }
    private static void normalizeV5(AccountRecord a) {
        Map<Integer, Long> seen = new TreeMap<>();
        if (a.exaltSeenByClass != null) a.exaltSeenByClass.forEach((id, at) -> { if (id != null && id > 0 && at != null && at >= 0) seen.put(id, at); });
        a.exaltSeenByClass = seen;
        if (a.vaultPotions != null && (a.vaultPotions.length != 8 || Arrays.stream(a.vaultPotions).anyMatch(n -> n < 0))) a.vaultPotions = null;
        if (a.vaultPotions == null || a.vaultPotionsObservedAt < 0) a.vaultPotionsObservedAt = 0;
    }
    private static final java.lang.reflect.Type COMPLETIONS = new TypeToken<Map<String, Integer>>() {}.getType(),
        SEEN_BY_CLASS = new TypeToken<Map<Integer, Long>>() {}.getType(), SEEN_TIMES = new TypeToken<Map<String, Long>>() {}.getType();
    /**
     * Only for checking v5 fields before binding: a boolean must be a JSON boolean, a number a JSON number with an exact integral
     * value in the Java type's range, and a string a JSON string. Gson alone coerces ("yes" reads as false, "123" as 123, 1.5 as 1,
     * 4294967297 as the int 1). JSON null is never passed to these, so null stays unknown.
     */
    private static final Gson STRICT = strictGson();
    private static Gson strictGson() {
        JsonDeserializer<Boolean> bool = (value, type, context) -> {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
            throw new JsonParseException("Not a boolean");
        };
        JsonDeserializer<Long> whole = (value, type, context) -> exact(value).longValueExact();
        JsonDeserializer<Integer> integer = (value, type, context) -> exact(value).intValueExact();
        JsonDeserializer<String> text = (value, type, context) -> {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString();
            throw new JsonParseException("Not a string");
        };
        return new GsonBuilder().registerTypeAdapter(Boolean.class, bool).registerTypeAdapter(boolean.class, bool)
            .registerTypeAdapter(Long.class, whole).registerTypeAdapter(long.class, whole)
            .registerTypeAdapter(Integer.class, integer).registerTypeAdapter(int.class, integer)
            .registerTypeAdapter(String.class, text).create();
    }
    /** A JSON number as written; longValueExact/intValueExact then reject fractions and out-of-range values (1e3 is 1000). */
    private static java.math.BigDecimal exact(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new JsonParseException("Not a number");
        return new java.math.BigDecimal(value.getAsString());
    }
    /** v5 fields are optional: one that does not parse as its type is removed before binding (read as unknown), never a failure. */
    private static void dropMalformedV5Fields(JsonElement tree) {
        if (tree == null || !tree.isJsonObject()) return;
        JsonElement characters = tree.getAsJsonObject().get("characters"), accounts = tree.getAsJsonObject().get("accounts");
        if (characters != null && characters.isJsonArray()) for (JsonElement row : characters.getAsJsonArray()) if (row.isJsonObject()) {
            JsonObject r = row.getAsJsonObject();
            drop(r, "pet", PetRecord.class); drop(r, "dungeonCompletions", COMPLETIONS); drop(r, "dungeonCompletionsObservedAt", long.class);
            drop(r, "exp", Long.class); drop(r, "hasBackpack", Boolean.class);
            drop(r, "equipmentEnchants", String[].class);
        }
        if (accounts != null && accounts.isJsonObject()) for (Map.Entry<String, JsonElement> row : accounts.getAsJsonObject().entrySet())
            if (row.getValue().isJsonObject()) {
                JsonObject a = row.getValue().getAsJsonObject();
                // Keys are class ids written as JSON names (strings): parsed leniently as today; the times are strict.
                drop(a, "exaltSeenByClass", SEEN_BY_CLASS, JSON); drop(a, "exaltSeenByClass", SEEN_TIMES);
                drop(a, "vaultPotions", int[].class); drop(a, "vaultPotionsObservedAt", long.class);
            }
    }
    private static void drop(JsonObject owner, String field, java.lang.reflect.Type type) { drop(owner, field, type, STRICT); }
    private static void drop(JsonObject owner, String field, java.lang.reflect.Type type, Gson gson) {
        JsonElement value = owner.get(field);
        if (value == null || value.isJsonNull()) return;
        try { gson.fromJson(value, type); } catch (RuntimeException malformed) { owner.remove(field); }
    }
    /** The captured EXALTED_* bonuses in canonical stat order, or null unless all eight were captured. */
    public static int[] exaltBonus(Entity player) {
        if (player == null) return null;
        int[] bonus = new int[8];
        for (int i = 0; i < 8; i++) {
            StatData value = player.stat.get(EXALT_BONUSES[i]);
            if (value == null || value.statValue < 0) return null;
            bonus[i] = value.statValue;
        }
        return bonus;
    }
    private static boolean validExalts(int[] v) { return v != null && v.length == 8 && Arrays.stream(v).allMatch(n -> n >= 0); }
    private AccountRecord account(String key) {
        AccountRecord a = document.accounts.get(key);
        if (a == null) { a = new AccountRecord(); a.key = key; document.accounts.put(key, a); }
        return a;
    }
    private CharacterRecord find(String key) { for (CharacterRecord r : document.characters) if (r.key.equals(key)) return r; return null; }
    private void changed() { dirty = true; revision++; }
    /** False when the saved journal could not be loaded; an absent file is a valid empty journal. */
    public synchronized boolean readable() { return !readOnly; }
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
    /** Deep copy of one account's record, or null when the account is unknown. */
    public synchronized AccountRecord accountCopy(String accountKey) {
        AccountRecord a = accountKey == null ? null : document.accounts.get(accountKey);
        return a == null ? null : copy(a);
    }
    /** Deep copy of one character's record by journal key ("<account>:<characterId>"), or null when unknown. */
    public synchronized CharacterRecord characterCopy(String key) {
        CharacterRecord r = key == null ? null : find(key);
        return r == null ? null : copy(r);
    }
    /** Null while the journal is readable and its last save (if any) succeeded; otherwise storageStatus(), for a warn banner. */
    public synchronized String storageProblem() {
        return readOnly || saveFailed ? storageStatus : null;
    }
    /**
     * Deep copy of the non-dead character last observed alive in game, or null. A character list sets lastSeen for every
     * character it names, so lastSeen only breaks ties, then file order.
     */
    public synchronized CharacterRecord mostRecentCharacter() {
        CharacterRecord best = null;
        for (CharacterRecord r : document.characters) if (!r.dead && (best == null || r.lastObservedAlive > best.lastObservedAlive
            || r.lastObservedAlive == best.lastObservedAlive && r.lastSeen > best.lastSeen)) best = r;
        return best == null ? null : copy(best);
    }
    public synchronized void markDead(String key, boolean dead) {
        CharacterRecord r = find(key); if (r == null) return;
        if (r.dead == dead) return;
        if (!dead) {
            CharacterRecord next = pendingAlive.remove(key);
            if (next != null) {
                next.notes = r.notes;
                next.deathAnnotation = copyAnnotation(r.deathAnnotation);
                document.characters.set(document.characters.indexOf(r), next);
                r = next;
            }
        } else pendingAlive.remove(key);
        r.observedAgainAt = 0;
        r.dead = dead; r.diedAt = dead ? System.currentTimeMillis() : 0; changed();
        if (dead) {
            if (r.deathAnnotation == null) r.deathAnnotation = new DeathAnnotation();
            r.deathAnnotation.markedAt = r.diedAt;
        }
    }
    public synchronized void annotateDeath(String key, DeathAnnotation value) {
        CharacterRecord r = find(key); if (r == null || readOnly) return;
        if (value == null) throw new IllegalArgumentException("Annotation is required");
        validateAnnotation(value);
        DeathAnnotation next = copyAnnotation(value);
        next.markedAt = r.deathAnnotation == null ? r.diedAt : r.deathAnnotation.markedAt;
        next.editedAt = System.currentTimeMillis(); r.deathAnnotation = next; changed();
    }
    private static void validateAnnotation(DeathAnnotation value) {
        if (value.markedAt < 0 || value.editedAt < 0 || value.occurredAt != null && value.occurredAt < 0)
            throw new IllegalArgumentException("Invalid annotation timestamp");
        if (value.notes != null && value.notes.length() > 10000) throw new IllegalArgumentException("Note is too long");
        if (value.visit != null && (value.visit.sessionId == null || value.visit.sessionId.isEmpty()
                || value.visit.visitId == null || value.visit.visitId.isEmpty())) throw new IllegalArgumentException("Invalid exact visit");
    }
    private static DeathAnnotation copyAnnotation(DeathAnnotation source) {
        if (source == null) return null;
        DeathAnnotation copy = new DeathAnnotation(); copy.occurredAt = source.occurredAt;
        copy.markedAt = source.markedAt; copy.editedAt = source.editedAt; copy.notes = source.notes; copy.visit = source.visit;
        return copy;
    }
    public synchronized void notes(String key, String notes) {
        CharacterRecord r = find(key); if (r == null) return;
        r.notes = notes == null ? "" : notes; changed();
    }
    /** "Saving locally…" while changes wait for the saver; otherwise the last load or save status (a failure keeps its text). */
    public synchronized String storageStatus() { return dirty && !readOnly && !saveFailed ? "Saving locally…" : storageStatus; }
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
            // Every failure below is caught, RuntimeExceptions included (an invalid path, say): one escaping save() would cancel
            // the scheduled saver for good, silently. A failure keeps dirty (and backupPending), so the next save retries.
            try {
                backupOnce(); // before version 5 first replaces an older file
            } catch (IOException | RuntimeException e) {
                // Name the backup path: a stuck/invalid journal.v4.bak is a different, more diagnosable problem than a plain
                // write failure, and "check access to Characters/journal.json" would point at the wrong file.
                // A failed backup leaves the older file untouched and backupPending/dirty set, so the saver retries both.
                String backup = backupPath(path).getFileName().toString();
                synchronized (this) {
                    saveFailed = true;
                    storageStatus = "Save failed • backup Characters/" + backup + " could not be written. " + path.getFileName()
                        + " is unchanged. Move or delete whatever is at " + backup + ", or free disk space; saving retries automatically.";
                }
                return;
            }
            try {
                store.write(path, JSON.toJson(snapshot));
                synchronized (this) {
                    if (revision == savedRevision) dirty = false;
                    saveFailed = false;
                    storageStatus = "Saved locally • Characters/journal.json";
                }
            } catch (IOException | RuntimeException e) {
                synchronized (this) { saveFailed = true; storageStatus = "Save failed • check access to Characters/journal.json"; }
            }
        }
    }

    /** The one-time copy of a pre-version-5 journal beside it: journal.json becomes journal.v4.bak. */
    static Path backupPath(Path path) {
        String name = path.getFileName().toString();
        return path.resolveSibling((name.endsWith(".json") ? name.substring(0, name.length() - 5) : name) + ".v4.bak");
    }
    /**
     * Copies the loaded pre-version-5 file once, never over an existing backup (saveLock held). A backup path occupied by
     * anything other than a regular file (a directory, say, from an interrupted earlier attempt) does not count as already
     * backed up, so the copy is attempted and its failure fails the save, rather than silently letting the version 5 write
     * proceed with no real backup ever made.
     */
    private void backupOnce() throws IOException {
        if (!backupPending) return;
        Path backup = backupPath(path);
        if (Files.exists(path) && !Files.isRegularFile(backup)) Files.copy(path, backup);
        backupPending = false;
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
        c.deathAnnotation = copyAnnotation(r.deathAnnotation);
        c.lastObservedAlive = r.lastObservedAlive; c.rosterReceivedAt = r.rosterReceivedAt; c.observedAgainAt = r.observedAgainAt;
        c.fields = new HashMap<>(r.fields);
        c.observationRevision = r.observationRevision;
        c.rosterRevision = r.rosterRevision;
        c.stats = r.stats.clone(); c.equipment = r.equipment.clone();
        c.equipmentEnchants = r.equipmentEnchants == null ? null : r.equipmentEnchants.clone();
        c.pet = copy(r.pet);
        c.dungeonCompletions = r.dungeonCompletions == null ? null : new TreeMap<>(r.dungeonCompletions);
        c.dungeonCompletionsObservedAt = r.dungeonCompletionsObservedAt; c.exp = r.exp; c.hasBackpack = r.hasBackpack;
        return c;
    }

    private static AccountRecord copy(AccountRecord a) {
        AccountRecord c = new AccountRecord(); c.key = a.key; c.name = a.name; c.exaltSeen = a.exaltSeen;
        a.exalts.forEach((id, values) -> c.exalts.put(id, values.clone()));
        a.liveExaltBonus.forEach((id, value) -> {
            ExaltBonus bonus = new ExaltBonus(); bonus.bonus = value.bonus.clone(); bonus.observedAt = value.observedAt;
            c.liveExaltBonus.put(id, bonus);
        });
        c.accountFame = a.accountFame; c.gold = a.gold; c.rankStars = a.rankStars; c.accountStatsObservedAt = a.accountStatsObservedAt;
        c.exaltSeenByClass = new TreeMap<>(a.exaltSeenByClass);
        c.vaultPotions = a.vaultPotions == null ? null : a.vaultPotions.clone(); c.vaultPotionsObservedAt = a.vaultPotionsObservedAt;
        return c;
    }

    private static PetRecord copy(PetRecord p) {
        if (p == null) return null;
        PetRecord c = new PetRecord();
        c.absent = p.absent; c.instanceId = p.instanceId; c.name = p.name; c.type = p.type; c.rarity = p.rarity; c.family = p.family; c.skin = p.skin;
        c.maxAbilityPower = p.maxAbilityPower; c.abilityType = p.abilityType.clone(); c.abilityLevel = p.abilityLevel.clone();
        c.abilityPoints = p.abilityPoints.clone(); c.observedAt = p.observedAt; c.source = p.source;
        return c;
    }

    private static boolean sameObservation(CharacterRecord a, CharacterRecord b) {
        return a.classId == b.classId && Objects.equals(a.className, b.className) && Objects.equals(a.name, b.name)
            && Objects.equals(a.level, b.level) && Objects.equals(a.skin, b.skin) && Objects.equals(a.fame, b.fame)
            && Objects.equals(a.seasonal, b.seasonal) && Arrays.equals(a.stats, b.stats) && Arrays.equals(a.equipment, b.equipment)
            && Arrays.equals(a.equipmentEnchants, b.equipmentEnchants);
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
    /**
     * The journal account key of an entity's own ACCOUNT_ID_STAT, or null when that stat is missing or blank. The one derivation
     * the journal, live identity and fame history share, so their keys always match.
     */
    static String accountKeyOf(Entity player) {
        StatData stat = player == null ? null : player.stat.get(StatType.ACCOUNT_ID_STAT);
        return stat == null || stat.stringStatValue == null || stat.stringStatValue.trim().isEmpty() ? null : accountKey(stat.stringStatValue);
    }
    public static String accountKey(String id) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
