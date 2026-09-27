package tomato.gui.glance.character;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.character.CharacterRosterQuery;
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
    public static CharacterJournal journal(Path file, long now) {
        CharacterJournal journal = new CharacterJournal(file);
        String account = journal.observe(CharacterJournalTest.player("synthetic-account", WIZARD), 101);
        List<RealmCharacter> roster = new ArrayList<>();
        for (int i = 0; i < ROSTER.size(); i++) roster.add(character(ROSTER.get(i), now - i * 3_600_000L));
        journal.mergeRoster(account, roster);
        journal.exalts(account, Map.of(WIZARD, WIZARD_EXALTS.clone()));
        for (Spec spec : ROSTER) if (spec.dead()) journal.markDead(ACCOUNT + ":" + spec.id(), true);
        return journal;
    }

    private static RealmCharacter character(Spec spec, long receivedAt) {
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
