package tomato.gui.glance.character;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.Stat;
import tomato.gui.character.CharacterRosterQuery;
import tomato.history.AppHistory;
import tomato.planning.PlanData;
import tomato.planning.PlanningMetadata;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.enums.CharacterClass;

/** Synthetic characters for the gallery, sheet, shell and evidence tests. No capture and no personal data. */
public final class CharacterFixtures {
    public static final String ACCOUNT = CharacterJournal.accountKey("synthetic-account");
    /** The synthetic Wizard: the Home fixture's hero (HomeModels.KEY) opens this sheet. */
    public static final String KEY = ACCOUNT + ":101";
    public static final int WIZARD = 782;
    /** Caps every fixture class shares (canonical order: life, mana, atk, def, spd, dex, vit, wis). */
    public static final int[] CAPS = {670, 385, 75, 25, 50, 75, 40, 60};
    /** The Wizard's exalt completions in RealmCharacter order (dex, spd, vit, wis, def, atk, mana, life): tiers 5+4+3+2+1+0+0+4 = 19. */
    public static final int[] WIZARD_EXALTS = {75, 50, 30, 15, 5, 1, 0, 74};
    private static final String[] CAP_TAGS = {"MaxHitPoints", "MaxMagicPoints", "Attack", "Defense", "Speed", "Dexterity", "HpRegen", "MpRegen"};

    /** One roster entry; {@code statMask} marks the captured base stats (fewer than eight leave "maxed" unknown). */
    private record Spec(int id, int classId, String className, int level, Long fame, boolean seasonal, int[] stats, int statMask, boolean dead) {}
    private static final List<Spec> ROSTER = List.of(
        new Spec(101, WIZARD, "Wizard", 20, 1_234L, false, new int[]{670, 385, 75, 25, 50, 75, 40, 57}, 255, false), // 7/8: WIS needs 3
        new Spec(102, 797, "Warrior", 20, 5_400L, true, CAPS, 255, false),                                        // 8/8, seasonal
        new Spec(103, 784, "Priest", 20, 880L, false, new int[]{620, 385, 70, 25, 45, 75, 40, 60}, 255, false),    // 5/8, needs Life
        new Spec(104, 798, "Knight", 14, null, false, new int[]{540, 200, 50, 10, 40, 0, 0, 0}, 0b11111, false),   // fame, maxed unknown
        new Spec(105, 775, "Archer", 20, 15_020L, false, new int[]{670, 385, 75, 20, 50, 70, 40, 60}, 255, false), // 6/8
        new Spec(106, 768, "Rogue", 20, 2_210L, true, CAPS, 255, false),                                          // 8/8, seasonal
        new Spec(107, 801, "Necromancer", 20, 640L, false, new int[]{600, 300, 70, 20, 50, 75, 40, 60}, 255, true), // 4/8, dead
        new Spec(108, 799, "Paladin", 18, 95L, false, new int[]{500, 300, 60, 20, 40, 60, 30, 50}, 255, true));    // 0/8, dead

    private CharacterFixtures() {}

    /** A journal at {@code file} with the eight characters above (107 and 108 marked dead) and the Wizard's exalts. */
    public static CharacterJournal journal(Path file, long now) { return journal(file, now, false); }

    /** The evidence journal's second account: hashed like every journal account, never observed, so the selector names it by its key. */
    public static final String SECOND_ACCOUNT = CharacterJournal.accountKey("synthetic-second-account");
    /** Exalt counts in canonical order (life, mana, atk, def, spd, dex, vit, wis) for the evidence journal's other classes. */
    private static final Map<Integer, int[]> EXALTS = Map.of(
        784, new int[]{75, 60, 55, 50, 75, 75, 50, 75},  // Priest: lowest tier 4, +20%
        797, new int[]{75, 30, 75, 30, 50, 30, 30, 30},  // Warrior: tier 3
        798, new int[]{40, 30, 30, 30, 30, 30, 30, 30},  // Knight: tier 3
        799, new int[]{30, 30, 30, 30, 30, 30, 30, 30},  // Paladin: tier 3; the group's lowest count 30 gives +15%
        775, new int[]{75, 75, 75, 75, 75, 75, 75, 75},  // Archer: every stat at tier 5, boost unknown (no weapon group)
        768, new int[]{75, 75, 75, 75, 75, 75, 75, 80},  // Rogue: every stat at tier 5, +25%
        801, new int[]{15, 5, 5, 0, 5, 5, 0, 5});        // Necromancer: tier 0

    /**
     * P3b evidence (Characters › Exalts and Pets): {@link #journal} plus two more equipped pets and exalt counts for every roster
     * class and a second account. The P3a captures and every other test keep the plain journal.
     * - Pets: the Archer (#105) carries "Second pet" (Rare, instance 5 105); the dead Necromancer (#107) carried the Wizard's
     *   "Sample pet" (instance 5 101) six hours earlier, so the gallery shows one card equipped by both.
     * - Exalts: the Wizard's WIZARD_EXALTS and seven more classes of this account (EXALTS); a second, never observed and so
     *   unnamed account (SECOND_ACCOUNT) holds a Knight and a Paladin.
     * With {@link #installWeaponGroups}, the tiles show +0% (Wizard, Necromancer: the Wizard has counts below 5), +20% (Priest),
     * +15% (Warrior, Knight, Paladin), +25% (Rogue) and "Loot —" (Archer: no weapon group in the fixture assets).
     */
    public static CharacterJournal evidenceJournal(Path file, long now) {
        CharacterJournal journal = journal(file, now, true);
        Map<Integer, int[]> exalts = new HashMap<>();
        for (Map.Entry<Integer, int[]> entry : EXALTS.entrySet()) exalts.put(entry.getKey(), ExaltFixtures.counts(entry.getValue()));
        journal.exalts(ACCOUNT, exalts);
        journal.exalts(SECOND_ACCOUNT, Map.of(798, ExaltFixtures.counts(15, 15, 20, 15, 15, 30, 15, 15), 799, ExaltFixtures.counts(5, 5, 5, 5, 5, 5, 5, 5)));
        return journal;
    }

    private static CharacterJournal journal(Path file, long now, boolean evidence) {
        CharacterJournal journal = new CharacterJournal(file);
        String account = journal.observe(CharacterJournalTest.player("synthetic-account", WIZARD), 101);
        List<RealmCharacter> roster = new ArrayList<>();
        for (int i = 0; i < ROSTER.size(); i++) roster.add(character(ROSTER.get(i), now - i * 3_600_000L, evidence));
        journal.mergeRoster(account, roster);
        journal.exalts(account, Map.of(WIZARD, WIZARD_EXALTS.clone()));
        for (Spec spec : ROSTER) if (spec.dead()) journal.markDead(ACCOUNT + ":" + spec.id(), true);
        return journal;
    }

    private static RealmCharacter character(Spec spec, long receivedAt, boolean evidence) {
        RealmCharacter c = new RealmCharacter();
        c.charId = spec.id(); c.classNum = (short) spec.classId(); c.classString = spec.className(); c.level = spec.level();
        c.seasonal = spec.seasonal();
        for (String field : new String[]{"class", "level", "seasonal"}) c.supplied(field);
        if (spec.fame() != null) { c.fame = spec.fame(); c.supplied("fame"); }
        int[] s = spec.stats();
        c.hp = s[0]; c.mp = s[1]; c.atk = s[2]; c.def = s[3]; c.spd = s[4]; c.dex = s[5]; c.vit = s[6]; c.wis = s[7];
        c.capturedStatMask = spec.statMask();
        // The Wizard's weapon, ability and armor are known, its ring and inventory empty, its backpack not captured.
        if (spec.id() == 101) c.equipment = new int[]{2593, 2856, 3113, -1, -1, -1, -1, -1, -1, -1, -1, -1};
        c.receivedAt = receivedAt;
        if (spec.id() == 101) pet(c);
        if (spec.id() == 103) c.supplied(RealmCharacter.PET_NONE); // the Priest's list entry has an empty pet element: "No pet"
        if (evidence && spec.id() == 105) // Rare "Second pet": Heal 50, Magic heal 45, Electric locked below max level 90
            pet(c, 5_105, "Second pet", 0x7002, 2, 70, new int[]{11_000, 50, 407, 7_400, 45, 408, 0, 1, 406}, receivedAt);
        if (evidence && spec.id() == 107) // the Wizard's pet, six hours earlier on the dead Necromancer (older values: Heal 88)
            pet(c, 5_101, "Sample pet", 0x7001, 3, 90, new int[]{200_000, 88, 407, 50_000, 70, 408, 4_000, 38, 406}, receivedAt);
        return c;
    }

    /**
     * The Wizard's pet as the character list reports it: "Sample pet", Legendary (rarity 3, max ability level 90), Heal level 90,
     * Magic heal level 72 and Electric level 40. Skin 0 (no sprite: the placeholder); type 0x7001 (named only if pets.xml says so).
     */
    private static void pet(RealmCharacter c) {
        c.petInstanceId = 5_101; c.petName = "Sample pet"; c.petType = 0x7001; c.petRarity = 3; c.petMaxAbilityPower = 90; c.petSkin = 0;
        // [points, level, type] per slot; points agree with the levels under PetFeeding's formula.
        c.petAbilitys = new int[]{236_000, 90, 407, 60_000, 72, 408, 5_000, 40, 406};
        for (int stat : new int[]{81, 82, 83, 84, 85, 87, 88, 89, 90, 91, 92, 93, 94, 95}) c.supplied("pet." + stat);
        c.supplied("pet." + packets.data.enums.StatType.SKIN_ID.get());
    }

    /** A pet the character list reports at {@code at}; {@code abilities}: points, level and type per slot (points agree with PetFeeding). */
    private static void pet(RealmCharacter c, int instanceId, String name, int type, int rarity, int max, int[] abilities, long at) {
        c.petInstanceId = instanceId; c.petName = name; c.petType = type; c.petRarity = rarity; c.petMaxAbilityPower = max; c.petSkin = 0;
        c.petAbilitys = abilities.clone();
        for (int stat : new int[]{81, 82, 83, 84, 85, 87, 88, 89, 90, 91, 92, 93, 94, 95}) c.supplied("pet." + stat, at, "Character list");
        c.supplied("pet." + StatType.SKIN_ID.get(), at, "Character list");
    }

    /**
     * Pet names for the evidence pets from a synthetic {@code xml/pets.xml} under {@code root}: 0x7001 (the Wizard's) "Canine",
     * 0x7002 (the Archer's) "Feline", 0x7003 (the Pet Yard's "Yard pet") "Aquatic". Install with PetDefinitions.install.
     */
    public static PetDefinitions petNames(Path root) throws IOException {
        Path xml = Files.createDirectories(root.resolve("xml")).resolve("pets.xml");
        Files.writeString(xml, "<Objects><Object type='0x7001' id='Sample'><Family>Canine</Family></Object>"
            + "<Object type='0x7002' id='Second'><Family>Feline</Family></Object><Object type='0x7003' id='Yard'><Family>Aquatic</Family></Object></Objects>");
        return PetDefinitions.read(root);
    }

    /**
     * The player enters the Pet Yard on {@link #ACCOUNT} at {@code at}: capture's pet scope moves to the account and sees two yard
     * pets: the Archer's "Second pet" (instance 5 105) with newer values (Heal 51), which merges into its equipped card, and "Yard
     * pet" (instance 5 200, Uncommon, max level 50, Heal 30, Magic heal 12, Electric locked), which no character equips.
     */
    public static void enterPetYard(ProgressionData source, long at) {
        source.reset(ACCOUNT, "Pet Yard visit");
        yardPet(source, 11, at, 5_105, "Second pet", 0x7002, 2, 70, new int[]{12_000, 51, 407, 7_400, 45, 408, 0, 1, 406});
        yardPet(source, 12, at, 5_200, "Yard pet", 0x7003, 1, 50, new int[]{2_150, 30, 407, 350, 12, 408, 0, 1, 406});
    }

    private static void yardPet(ProgressionData source, int objectId, long at, int instanceId, String name, int type, int rarity, int max, int[] abilities) {
        StatType[] slots = {StatType.PET_FIRST_ABILITY_POINT_STAT, StatType.PET_FIRST_ABILITY_POWER_STAT, StatType.PET_FIRST_ABILITY_TYPE_STAT,
            StatType.PET_SECOND_ABILITY_POINT_STAT, StatType.PET_SECOND_ABILITY_POWER_STAT, StatType.PET_SECOND_ABILITY_TYPE_STAT,
            StatType.PET_THIRD_ABILITY_POINT_STAT, StatType.PET_THIRD_ABILITY_POWER_STAT, StatType.PET_THIRD_ABILITY_TYPE_STAT};
        List<StatData> stats = new ArrayList<>(List.of(stat(StatType.PET_INSTANCE_ID_STAT, instanceId), stat(StatType.PET_TYPE_STAT, type),
            stat(StatType.PET_RARITY_STAT, rarity), stat(StatType.PET_MAX_ABILITY_POWER_STAT, max), stat(StatType.SKIN_ID, 0)));
        StatData label = stat(StatType.PET_NAME_STAT, 0); label.stringStatValue = name; stats.add(label);
        for (int i = 0; i < slots.length; i++) stats.add(stat(slots[i], abilities[i]));
        source.pet(source.scope(), objectId, new Stat(stats.toArray(new StatData[0])), at, "Pet Yard capture", Collections.emptyMap());
    }

    private static StatData stat(StatType type, int value) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; return stat;
    }

    /**
     * Saved fame history of the Wizard (KEY) under a SessionStore {@code root}, relative to {@code now}, for Sheet › Fame:
     * - two days ago (a 45-minute session): 1,000, 1,060, 1,120 and 1,180 over 40 minutes; yesterday (30 minutes): 1,180, 1,210
     *   and 1,234 over 25 minutes. Every one of these readings carries ACCOUNT.
     * - three readings of character id 101 written before readings recorded the account (the "older readings" caption), and one
     *   of SECOND_ACCOUNT's #101, which the Fame tab never shows.
     */
    public static void fameHistory(Path root, long now) throws IOException {
        long minute = 60_000L, first = now - 2 * 24 * 3_600_000L, second = now - 24 * 3_600_000L;
        String a = FameFixtures.id("evidence-first"), b = FameFixtures.id("evidence-second");
        FameFixtures.session(root, a, first, first + 45 * minute);
        FameFixtures.fame(root, a, fame(ACCOUNT, 1_000, first + minute), legacy(940, first + 2 * minute), fame(ACCOUNT, 1_060, first + 12 * minute),
            fame(SECOND_ACCOUNT, 7_700, first + 13 * minute), legacy(990, first + 20 * minute), fame(ACCOUNT, 1_120, first + 25 * minute),
            fame(ACCOUNT, 1_180, first + 41 * minute));
        FameFixtures.session(root, b, second, second + 30 * minute);
        FameFixtures.fame(root, b, fame(ACCOUNT, 1_180, second + minute), legacy(1_010, second + 3 * minute), fame(ACCOUNT, 1_210, second + 14 * minute),
            fame(ACCOUNT, 1_234, second + 26 * minute));
    }

    private static AppHistory.FameSample fame(String account, long fame, long time) { return new AppHistory.FameSample(101, account, fame, time, "Wizard"); }
    private static AppHistory.FameSample legacy(long fame, long time) { return new AppHistory.FameSample(101, fame, time, "Wizard"); }

    /**
     * {@code plan} plus the Wizard's goals as Manage goals pins them: stat goals WIS → 60 (3 potions to go), Life → 670 and SPD → 50
     * (complete), and Wizard exalt goals Life tier 5 (74 of 75), Dex tier 5 (complete) and Wis tier 3 (15 of 30).
     */
    public static PlanData.AccountPlan goals(PlanData.AccountPlan plan, RosterDefinitions defs, PlanningMetadata metadata, long now) {
        PlanData.AccountPlan next = PlanData.copy(plan);
        for (int[] goal : new int[][]{{7, 60}, {0, 670}, {4, 50}}) SheetFixtures.statGoal(next, KEY, goal[0], goal[1], defs, now);
        for (int[] goal : new int[][]{{0, 5}, {5, 5}, {7, 3}}) SheetFixtures.exaltGoal(next, WIZARD, goal[0], goal[1], metadata, now);
        return next;
    }

    /**
     * Makes {@code CharacterClass.weaponClasses} name synthetic weapon groups until the returned handle is closed: Wizard and
     * Necromancer; Warrior, Knight and Paladin; the Priest alone; the Rogue alone. The Archer has none (its loot boost is unknown).
     */
    public static AutoCloseable installWeaponGroups() throws ReflectiveOperationException {
        TreeMap<Integer, int[]> groups = new TreeMap<>();
        for (int[] group : new int[][]{{WIZARD, 801}, {797, 798, 799}, {784}, {768}}) for (int id : group) groups.put(id, group.clone());
        Runnable undo = swap(CharacterClass.class, "WEAPON_CLASSES", groups);
        return undo::run;
    }

    /**
     * Makes {@code PlanningMetadata.current()} the dungeon mapping {@link SheetFixtures#dungeonMapping} reads under {@code root}
     * (Life is earned in "Fixture Vault" and "Second Vault") until the returned handle is closed. Waits for a running asset read.
     */
    public static AutoCloseable installDungeonMapping(Path root) throws Exception {
        PlanningMetadata mapping = SheetFixtures.dungeonMapping(root);
        PlanningMetadata.current(); // starts the asset read for this root when none ran yet
        Field current = field(PlanningMetadata.class, "current"), requested = field(PlanningMetadata.class, "requested");
        Field running = field(PlanningMetadata.class, "running");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            synchronized (PlanningMetadata.class) {
                if (!running.getBoolean(null)) {
                    Object previous = current.get(null), previousRoot = requested.get(null);
                    current.set(null, mapping);
                    requested.set(null, assets.AssetCache.root());
                    return () -> { synchronized (PlanningMetadata.class) { current.set(null, previous); requested.set(null, previousRoot); } };
                }
            }
            if (System.nanoTime() > end) throw new AssertionError("The dungeon mapping reader did not finish");
            Thread.sleep(20);
        }
    }

    /** Class caps for every fixture class, as RosterDefinitions would read them from players.xml. */
    public static RosterDefinitions definitions() {
        StringBuilder xml = new StringBuilder("<Objects>");
        for (Spec spec : ROSTER) {
            if (xml.indexOf("type='" + spec.classId() + "'") >= 0) continue;
            xml.append("<Object type='").append(spec.classId()).append("'>");
            for (int i = 0; i < 8; i++) xml.append('<').append(CAP_TAGS[i]).append(" max='").append(CAPS[i]).append("'/>");
            xml.append("</Object>");
        }
        try { return RosterDefinitions.parse(new StringReader(xml.append("</Objects>").toString()), null); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    /** A saved record: {@code below} stats one short of CAPS counting down from WIS, or -1 for an uncaptured WIS (maxed unknown). */
    public static CharacterJournal.CharacterRecord record(int id, int classId, String className, Integer level, Long fame, int below,
                                                          long played, long seen, boolean dead) {
        CharacterJournal.CharacterRecord r = new CharacterJournal.CharacterRecord();
        r.key = ACCOUNT + ":" + id; r.account = ACCOUNT; r.characterId = id; r.classId = classId; r.className = className;
        r.name = "Sample"; r.level = level; r.fame = fame; r.lastObservedAlive = played; r.lastSeen = seen; r.dead = dead;
        for (int i = 0; i < 8; i++) r.stats[i] = CAPS[i] - (7 - i < below ? 1 : 0);
        if (below < 0) r.stats[7] = null;
        return r;
    }

    public static CharacterRosterQuery.Row row(RosterDefinitions definitions, int id, int classId, String className, Integer level, Long fame,
                                               int below, long played, long seen, boolean dead) {
        return new CharacterRosterQuery.Row(record(id, classId, className, level, fame, below, played, seen, dead), definitions);
    }

    /** {@code count} rows across the fixture classes: every tenth dead, every seventh with maxed unknown, every eleventh fame unknown. */
    public static List<CharacterRosterQuery.Row> manyRows(RosterDefinitions definitions, int count, long now) {
        List<CharacterRosterQuery.Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Spec spec = ROSTER.get(i % ROSTER.size());
            rows.add(row(definitions, 1_000 + i, spec.classId(), spec.className(), 1 + i % 20, i % 11 == 0 ? null : 100L * i,
                i % 7 == 0 ? -1 : i % 9, now - i * 60_000L, now - i * 30_000L, i % 10 == 0));
        }
        return rows;
    }

    /** A card last played (and last seen) at {@code lastPlayed}. */
    public static CharacterCardModel card(int id, String className, Integer level, Long fame, Integer maxed, Boolean seasonal,
                                          boolean playingNow, boolean dead, long lastPlayed) {
        return new CharacterCardModel(ACCOUNT + ":" + id, "Sample", WIZARD, className, null, level, fame, maxed, seasonal, lastPlayed, lastPlayed, playingNow, dead);
    }

    /** The Wizard (KEY) in game: the roster's base stats with +50 Life and +12 on the others. */
    public static LiveCharacter.Snapshot live(long now) {
        int[] base = ROSTER.get(0).stats().clone(), totals = new int[8];
        for (int i = 0; i < 8; i++) totals[i] = base[i] + (i == 0 ? 50 : 12);
        return new LiveCharacter.Snapshot(ACCOUNT, 101, WIZARD, "Sample", null, 20, 1_234L, totals, base, new int[]{2593, 2856, 3113, -1},
            null, null, null, null, null, now);
    }

    /**
     * Makes shell tests deterministic until the returned handle is closed: RosterDefinitions.current() returns definitions(), and
     * CharacterClass knows the fixture classes' names and CAPS (other classes keep theirs). Waits for a running asset read first.
     */
    public static AutoCloseable installDefinitions() throws Exception {
        List<Runnable> undo = new ArrayList<>();
        Map<Integer, int[]> knownCaps = get(CharacterClass.class, "CLASS_MAX_STATS");
        Map<Integer, String> knownNames = get(CharacterClass.class, "CLASS_NAME");
        TreeMap<Integer, int[]> caps = new TreeMap<>(knownCaps);
        TreeMap<Integer, String> names = new TreeMap<>(knownNames);
        for (Spec spec : ROSTER) { caps.put(spec.classId(), CAPS.clone()); names.put(spec.classId(), spec.className()); }
        undo.add(swap(CharacterClass.class, "CLASS_MAX_STATS", caps));
        undo.add(swap(CharacterClass.class, "CLASS_NAME", names));
        RosterDefinitions.current(); // starts the asset read for this root when none ran yet
        Field lock = field(RosterDefinitions.class, "LOCK"), running = field(RosterDefinitions.class, "running");
        Field current = field(RosterDefinitions.class, "current");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            synchronized (lock.get(null)) {
                if (!running.getBoolean(null)) {
                    Object previous = current.get(null);
                    current.set(null, definitions());
                    undo.add(() -> {
                        try { synchronized (lock.get(null)) { current.set(null, previous); } }
                        catch (IllegalAccessException e) { throw new AssertionError(e); }
                    });
                    break;
                }
            }
            if (System.nanoTime() > end) throw new AssertionError("The roster definitions reader did not finish");
            Thread.sleep(20);
        }
        return () -> { for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run(); };
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Class<?> type, String name) throws ReflectiveOperationException { return (T) field(type, name).get(null); }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Runnable swap(Class<?> type, String name, Object value) throws ReflectiveOperationException {
        Field field = field(type, name);
        Object previous = field.get(null);
        field.set(null, value);
        return () -> { try { field.set(null, previous); } catch (IllegalAccessException e) { throw new AssertionError(e); } };
    }
}
