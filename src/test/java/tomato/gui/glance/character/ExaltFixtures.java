package tomato.gui.glance.character;

import java.nio.file.Path;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.LiveCharacter;

/**
 * Synthetic account exalts for the Characters › Exalts grid: two hashed synthetic accounts, four classes and explicit weapon
 * groups (tests never read game assets for them). No capture and no personal data.
 */
final class ExaltFixtures {
    static final int WIZARD = 782, PRIEST = 784, WARRIOR = 797, KNIGHT = 798, ARCHER = 775;
    static final String FIRST = CharacterJournal.accountKey("exalt-fixture-first"), SECOND = CharacterJournal.accountKey("exalt-fixture-second");
    static final long NOW = 1_700_000_000_000L, HOUR = 3_600_000L;
    /** The first account's Wizard, canonical order (life … wis): tiers 3 4 3 5 3 3 4 3, total 365, lowest 3. */
    static final int[] WIZARD_COUNTS = {30, 50, 30, 75, 40, 45, 60, 35};
    /** The first account's Priest: every stat at 75 or more, lowest tier 5 (a fully exalted class). */
    static final int[] PRIEST_COUNTS = {75, 75, 80, 75, 75, 75, 75, 75};
    /** The first account's Warrior: tiers 1 0 0 0 0 0 0 0, lowest 0. */
    static final int[] WARRIOR_COUNTS = {5, 0, 0, 0, 0, 0, 0, 0};
    /** The second account's Knight: every stat at 15, tier 2. */
    static final int[] KNIGHT_COUNTS = {15, 15, 15, 15, 15, 15, 15, 15};

    private ExaltFixtures() {}

    /** Counts given in canonical stat order, stored in RealmCharacter order (dex, spd, vit, wis, def, atk, mana, life) as the journal keeps them. */
    static int[] counts(int... canonical) {
        int[] stored = new int[8];
        for (int i = 0; i < 8; i++) stored[CharacterJournal.EXALT_ORDER[i]] = canonical[i];
        return stored;
    }

    /** Wizard and Priest share one weapon, Warrior and Knight another; Archer (and anything else) is missing from the assets: null. */
    static int[] weaponGroup(int classId) {
        if (classId == WIZARD || classId == PRIEST) return new int[] {WIZARD, PRIEST};
        if (classId == WARRIOR || classId == KNIGHT) return new int[] {WARRIOR, KNIGHT};
        return null;
    }

    static String className(int classId) {
        switch (classId) {
            case WIZARD: return "Wizard";
            case PRIEST: return "Priest";
            case WARRIOR: return "Warrior";
            case KNIGHT: return "Knight";
            case ARCHER: return "Archer";
            default: return "Class " + classId;
        }
    }

    /** An account record with {@code name} (null = never observed) and, per pair, a class id and its canonical counts. */
    static AccountRecord account(String key, String name, Object... classAndCounts) {
        AccountRecord a = new AccountRecord();
        a.key = key; a.name = name;
        for (int i = 0; i + 1 < classAndCounts.length; i += 2) a.exalts.put((Integer) classAndCounts[i], counts((int[]) classAndCounts[i + 1]));
        return a;
    }

    /** The first account: Priest, Wizard, Warrior (inserted out of class order), each class's counts last changed at a distinct time. */
    static AccountRecord first() {
        AccountRecord a = account(FIRST, "Sample", PRIEST, PRIEST_COUNTS, WIZARD, WIZARD_COUNTS, WARRIOR, WARRIOR_COUNTS);
        a.exaltSeenByClass.put(WIZARD, NOW - HOUR); a.exaltSeenByClass.put(PRIEST, NOW - 2 * HOUR); a.exaltSeenByClass.put(WARRIOR, NOW - 3 * HOUR);
        return a;
    }

    /** The second account (no saved name): only a Knight. */
    static AccountRecord second() { return account(SECOND, null, KNIGHT, KNIGHT_COUNTS); }

    static CharacterRecord record(String account, int characterId, int classId, long lastObservedAlive, boolean dead) {
        CharacterRecord r = new CharacterRecord();
        r.account = account; r.characterId = characterId; r.key = account + ":" + characterId; r.classId = classId;
        r.lastObservedAlive = lastObservedAlive; r.lastSeen = lastObservedAlive; r.dead = dead;
        return r;
    }

    /** The character in game: only the account, character and class matter to the grid. */
    static LiveCharacter.Snapshot live(String account, int characterId, int classId) {
        return new LiveCharacter.Snapshot(account, characterId, classId, "Sample", null, null, null, null, null, null, null, null, null,
            null, null, NOW);
    }

    /**
     * A journal at {@code file}: the first account plays a Wizard #7 (saved name "Sample") and holds the first() counts; with
     * {@code second}, the second account (never observed, so unnamed) also holds the Knight's counts.
     */
    static CharacterJournal journal(Path file, boolean second) {
        CharacterJournal journal = new CharacterJournal(file);
        journal.observe(CharacterJournalTest.player("exalt-fixture-first", WIZARD), 7);
        journal.exalts(FIRST, first().exalts);
        if (second) journal.exalts(SECOND, second().exalts);
        return journal;
    }
}
