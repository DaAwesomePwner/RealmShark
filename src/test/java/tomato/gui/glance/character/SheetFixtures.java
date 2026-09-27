package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.TreeMap;
import tomato.backend.data.*;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.gui.myinfo.BuildEstimates;
import tomato.planning.CharacterGoals;
import tomato.planning.PlanData;
import tomato.planning.PlanningMetadata;
import tomato.realmshark.enums.CharacterClass;

/** Synthetic character-sheet inputs: one Wizard of a hashed synthetic account. No capture and no personal data. */
public final class SheetFixtures {
    public static final int WIZARD = 782, PRIEST = 784;
    public static final String ACCOUNT = CharacterJournal.accountKey("sheet-fixture"), KEY = ACCOUNT + ":7";
    public static final long NOW = 1_700_000_000_000L, HOUR = 3_600_000L;

    private SheetFixtures() {}

    /**
     * Makes {@code CharacterClass.getName(classId)} resolve to {@code name} until the returned handle is closed: unlike
     * CharacterFixtures.installDefinitions, this does not wait on RosterDefinitions, so it is cheap in a narrow unit test that
     * only needs a live character's class name (e.g. the Build pointer, worded by class since the game's name stat is the
     * account's). Real game asset names (if any loaded) are restored on close.
     */
    @SuppressWarnings("unchecked")
    public static AutoCloseable className(int classId, String name) throws ReflectiveOperationException {
        Field field = CharacterClass.class.getDeclaredField("CLASS_NAME");
        field.setAccessible(true);
        TreeMap<Integer, String> previous = (TreeMap<Integer, String>) field.get(null);
        TreeMap<Integer, String> next = new TreeMap<>(previous);
        next.put(classId, name);
        field.set(null, next);
        return () -> field.set(null, previous);
    }

    /**
     * Wizard caps (life, mana, atk, def, spd, dex, vit, wis) = 720, 252, 75, 25, 50, 75, 40, 60, from a players.xml fragment, and
     * three items from an equip.xml fragment: 2_001 "UT", 2_004 "T6" (numeric tier), 2_010 "T12" (an explicit tier label).
     * Items 2_003 (the live armor) and 3_000 have no definition, so their slots carry no tier label.
     */
    public static RosterDefinitions defs() {
        String xml = "<Objects><Object type=\"782\"><MaxHitPoints max=\"720\"/><MaxMagicPoints max=\"252\"/><Attack max=\"75\"/>"
            + "<Defense max=\"25\"/><Speed max=\"50\"/><Dexterity max=\"75\"/><HpRegen max=\"40\"/><MpRegen max=\"60\"/></Object></Objects>";
        String equipment = "<Objects><Object type=\"2001\"><Labels>UT,WEAPON</Labels><Tier>14</Tier></Object>"
            + "<Object type=\"2004\"><Tier>6</Tier></Object><Object type=\"2010\"><Labels>T12,ARMOR</Labels><Tier>11</Tier></Object></Objects>";
        try { return RosterDefinitions.parse(new StringReader(xml), new StringReader(equipment)); } catch (IOException e) { throw new AssertionError(e); }
    }

    /** Wizard #7, 5 of 8 maxed (DEF needs 5, VIT 3, WIS 12); weapon and ring equipped, ability empty, armor not captured. */
    public static CharacterRecord record() {
        CharacterRecord r = new CharacterRecord();
        r.key = KEY; r.account = ACCOUNT; r.characterId = 7; r.classId = WIZARD; r.className = "Wizard"; r.name = "Sharkbait";
        r.level = 20; r.skin = 0; r.fame = 1_234L; r.seasonal = Boolean.TRUE; r.lastSeen = NOW - 2 * HOUR;
        int[] base = {720, 252, 75, 20, 50, 75, 37, 48};
        for (int i = 0; i < 8; i++) { r.stats[i] = base[i]; r.fields.put("stat." + i, new FieldCapture(r.lastSeen, "Captured total minus boost")); }
        r.equipment[0] = 2_001; r.equipment[1] = -1; r.equipment[3] = 2_004; r.equipment[4] = 2_010;
        for (int i = 5; i < 12; i++) r.equipment[i] = -1;
        return r;
    }

    /** An account whose Wizard exalt counts are given in canonical stat order (stored in RealmCharacter order); none when not 8. */
    public static AccountRecord account(int... canonical) {
        AccountRecord a = new AccountRecord(); a.key = ACCOUNT;
        if (canonical.length == 8) {
            int[] counts = new int[8];
            for (int i = 0; i < 8; i++) counts[CharacterJournal.EXALT_ORDER[i]] = canonical[i];
            a.exalts.put(WIZARD, counts);
        }
        return a;
    }

    public static CharacterJournal.ExaltBonus bonus(long observedAt, int... canonical) {
        CharacterJournal.ExaltBonus bonus = new CharacterJournal.ExaltBonus(); bonus.bonus = canonical.clone(); bonus.observedAt = observedAt;
        return bonus;
    }

    /** A live Wizard: same base as record(), totals 800/300/90/30/60/80/50/70, gear weapon/empty/armor/ring, live exalt bonus 9s. */
    public static LiveCharacter.Snapshot live(String account, int characterId, String name, BuildEstimates.Inputs build) {
        return new LiveCharacter.Snapshot(account, characterId, WIZARD, name, 0, 20, 1_500L, new int[]{800, 300, 90, 30, 60, 80, 50, 70},
            new int[]{720, 252, 75, 20, 50, 75, 37, 48}, new int[]{2_001, 0, 2_003, 2_004}, 10_000, 500, 40,
            new int[]{9, 9, 9, 9, 9, 9, 9, 9}, build, NOW - 500);
    }

    /** The builder with the fixture caps, no dungeon mapping (as while PlanningMetadata loads) and no pet names (as while PetDefinitions loads). */
    public static SheetModel model(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live) {
        return model(record, account, live, PetDefinitions.loading());
    }

    /** {@link #model(CharacterRecord, AccountRecord, LiveCharacter.Snapshot)} with the given pet names. */
    public static SheetModel model(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live, PetDefinitions pets) {
        return SheetModelBuilder.build(record, account, live, pets, defs(), null, NOW);
    }

    /**
     * A Rare pet (max ability level 70) as the character list reports it, observed at {@code observedAt}: Heal level 45 (7,200
     * points), Magic heal level 30 (2,100 points) and Electric, locked below max level 90 (level 1, 0 points). Type 0x7001, which
     * {@link #petNames} names "Canine"; skin 0 (no sprite: the placeholder).
     */
    public static CharacterJournal.PetRecord pet(long observedAt) {
        CharacterJournal.PetRecord p = new CharacterJournal.PetRecord();
        p.instanceId = 5_001L; p.name = "Sample pet"; p.type = 0x7001; p.rarity = 2; p.skin = 0; p.maxAbilityPower = 70;
        p.abilityType = new int[]{407, 408, 406}; p.abilityLevel = new int[]{45, 30, 1}; p.abilityPoints = new int[]{7_200, 2_100, 0};
        p.observedAt = observedAt; p.source = "Character list";
        return p;
    }

    /** A known "No pet": the character list reported an empty pet element. */
    public static CharacterJournal.PetRecord noPet(long observedAt) {
        CharacterJournal.PetRecord p = new CharacterJournal.PetRecord();
        p.absent = Boolean.TRUE; p.observedAt = observedAt; p.source = "Character list";
        return p;
    }

    /**
     * Pet names read from a synthetic {@code xml/pets.xml} under {@code root} (PetDefinitions.parse is package-private): type 0x7001
     * is "Canine"; no other type is named.
     */
    public static PetDefinitions petNames(java.nio.file.Path root) throws IOException {
        java.nio.file.Path xml = root.resolve("xml/pets.xml");
        java.nio.file.Files.createDirectories(xml.getParent());
        java.nio.file.Files.writeString(xml, "<Objects><Object type=\"0x7001\" id=\"Sample\"><Family>Canine</Family></Object></Objects>");
        return PetDefinitions.read(root);
    }

    /** Pins a stat goal for the Wizard with journal key {@code key}, as Manage goals does (the target must be within the cap). */
    public static void statGoal(PlanData.AccountPlan plan, String key, int stat, int target, RosterDefinitions defs, long now) {
        CharacterRecord r = new CharacterRecord(); r.key = key; r.classId = WIZARD;
        CharacterGoals.pinCharacter(plan, r, stat, target, defs, now);
    }

    /** Pins an exalt goal for {@code classId}, stamped with {@code metadata}'s version, as Manage goals does. */
    public static void exaltGoal(PlanData.AccountPlan plan, int classId, int stat, int tier, PlanningMetadata metadata, long now) {
        CharacterGoals.pinExalt(plan, classId, stat, tier, metadata, now);
    }

    /** A dungeon mapping read from a synthetic {@code xml/exaltationConfig.xml} under {@code root}: Life is earned in two dungeons, no other stat is mapped. */
    public static PlanningMetadata dungeonMapping(java.nio.file.Path root) throws IOException {
        java.nio.file.Path xml = root.resolve("xml/exaltationConfig.xml");
        java.nio.file.Files.createDirectories(xml.getParent());
        java.nio.file.Files.writeString(xml, "<Exaltation><Dungeons><Dungeon><Name>Fixture Vault</Name><PowerUp>LIFE</PowerUp></Dungeon>"
            + "<Dungeon><Name>Second Vault</Name><PowerUp>LIFE</PowerUp></Dungeon></Dungeons></Exaltation>");
        return PlanningMetadata.read(root);
    }

    /** {@code model} with only its identity's lastSeen moved: what two rebuilds of an unchanged character differ by while playing. */
    public static SheetModel seenAgain(SheetModel model, long lastSeen) {
        SheetModel.Identity i = model.identity();
        SheetModel.Identity moved = new SheetModel.Identity(i.name(), i.classId(), i.className(), i.skin(), i.level(), i.fame(), i.seasonal(),
            i.dead(), lastSeen, i.lastPlayed(), i.playing(), i.maxed());
        return new SheetModel(model.key(), moved, model.stats(), model.gear(), model.exalts(), model.pet(), model.death(), model.live());
    }

    /** Observes one Wizard #7 of the synthetic account (name "Sample") and returns its journal key. */
    public static String seed(CharacterJournal journal) {
        journal.observe(CharacterJournalTest.player("sheet-fixture", WIZARD), 7);
        return KEY;
    }

    /** Points TomatoData at a test journal; its lazy default reads Characters/journal.json in the working directory. */
    public static void inject(TomatoData data, CharacterJournal journal) {
        try { Field field = TomatoData.class.getDeclaredField("characterJournal"); field.setAccessible(true); field.set(data, journal); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** The first descendant of {@code type} (depth first), or null. */
    public static <T> T find(Container root, Class<T> type) { return search(root, null, type); }

    public static int count(Container root, Class<?> type) {
        int count = 0;
        for (Component child : root.getComponents()) count += (type.isInstance(child) ? 1 : 0) + (child instanceof Container ? count((Container) child, type) : 0);
        return count;
    }

    public static <T extends Component> T named(Container root, String name, Class<T> type) {
        T found = search(root, name, type);
        if (found == null) throw new AssertionError("No " + type.getSimpleName() + " named " + name);
        return found;
    }

    private static <T> T search(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && (name == null || name.equals(child.getName()))) return type.cast(child);
            if (child instanceof Container) { T found = search((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
