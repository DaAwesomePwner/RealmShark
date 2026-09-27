package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import tomato.backend.data.*;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.gui.myinfo.BuildEstimates;

/** Synthetic character-sheet inputs: one Wizard of a hashed synthetic account. No capture and no personal data. */
public final class SheetFixtures {
    public static final int WIZARD = 782, PRIEST = 784;
    public static final String ACCOUNT = CharacterJournal.accountKey("sheet-fixture"), KEY = ACCOUNT + ":7";
    public static final long NOW = 1_700_000_000_000L, HOUR = 3_600_000L;

    private SheetFixtures() {}

    /** Wizard caps (life, mana, atk, def, spd, dex, vit, wis) = 720, 252, 75, 25, 50, 75, 40, 60, from a players.xml fragment. */
    public static RosterDefinitions defs() {
        String xml = "<Objects><Object type=\"782\"><MaxHitPoints max=\"720\"/><MaxMagicPoints max=\"252\"/><Attack max=\"75\"/>"
            + "<Defense max=\"25\"/><Speed max=\"50\"/><Dexterity max=\"75\"/><HpRegen max=\"40\"/><MpRegen max=\"60\"/></Object></Objects>";
        try { return RosterDefinitions.parse(new StringReader(xml), null); } catch (IOException e) { throw new AssertionError(e); }
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

    /** The builder with the fixture caps and no dungeon mapping (as while PlanningMetadata loads). */
    public static SheetModel model(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live) {
        return SheetModelBuilder.build(record, account, live, defs(), null, NOW);
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
