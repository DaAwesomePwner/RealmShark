package tomato.backend.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.RealmCharacterStats;
import tomato.realmshark.enums.CharacterStatistics;
import static org.junit.Assert.*;

/** Journal v5: pet, dungeon completions, experience, backpack, per-class exalt times and vault potions. */
public class CharacterJournalV5Test {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String ACCOUNT = CharacterJournal.accountKey("v5-fixture"), KEY = ACCOUNT + ":7";
    private Path file() { return temp.getRoot().toPath().resolve("Characters/journal.json"); }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static void write(Path path, String json) throws Exception { Files.createDirectories(path.getParent()); Files.write(path, json.getBytes(StandardCharsets.UTF_8)); }
    private static String v3() { return CharacterJournalV4Test.v3Document(ACCOUNT); }

    /** PCStats with one Pirate Cave count (the encoding CharacterPublicationTest uses). */
    static String completions(int count) {
        byte[] bytes = new byte[21]; bytes[0] = 1;
        int bit = CharacterStatistics.PIRATE_CAVE.getPcStatId();
        bytes[4 + bit / 8] = (byte) (1 << (bit % 8)); bytes[20] = (byte) count;
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
    private static int[] counts(int pirateCave) {
        int[] counts = new int[CharacterStatistics.DUNGEON_NAMES.size()];
        counts[CharacterStatistics.getDungeonIndex("Pirate Cave")] = pirateCave;
        return counts;
    }
    /** A character-list entry with everything v5 reads, marked supplied as the metadata parser marks it. */
    private static RealmCharacter listed(long at, int pirateCave) {
        RealmCharacter c = new RealmCharacter(); c.charId = 7; c.receivedAt = at;
        c.exp = 30_000; c.supplied("exp", at, "Character list"); c.backpack = true; c.supplied("backpack", at, "Character list");
        c.pcStats = completions(pirateCave); c.charStats = new RealmCharacterStats(); c.charStats.decode(c.pcStats); c.supplied("dungeons", at, "Character list");
        c.petName = "Pup"; c.petInstanceId = 42; c.petType = 3; c.petRarity = 2; c.petSkin = 100; c.petMaxAbilityPower = 70;
        for (int field : new int[]{81, 82, 83, 84, 85, 25, 87, 88, 89, 90, 91, 92, 93, 94, 95}) c.supplied("pet." + field, at, "Character list");
        c.petAbilitys = new int[]{1000, 50, 407, 800, 40, 408, 600, 30, 406};
        return c;
    }

    @Test public void versionFourLoadsWithTheNewFieldsUnknownSavesAsFiveAndSixStaysReadOnly() throws Exception {
        Path path = file(); write(path, v3().replace("\"version\":3", "\"version\":4"));
        CharacterJournal j = new CharacterJournal(path);
        assertTrue(j.readable());
        CharacterJournal.CharacterRecord r = j.characterCopy(KEY);
        assertEquals("Sample", r.name);
        assertNull(r.pet); assertNull(r.dungeonCompletions); assertEquals(0, r.dungeonCompletionsObservedAt); assertNull(r.exp); assertNull(r.hasBackpack);
        CharacterJournal.AccountRecord a = j.accountCopy(ACCOUNT);
        assertTrue(a.exaltSeenByClass.isEmpty()); assertNull(a.vaultPotions); assertEquals(0, a.vaultPotionsObservedAt);
        assertNull("An unknown key", j.characterCopy(ACCOUNT + ":99")); assertNull(j.characterCopy(null));
        j.vaultPotions(ACCOUNT, new int[]{3, 1, 2, 1, 0, 0, 0, 0}, 5_000); j.save();
        assertTrue(read(path).contains("\"version\": 5"));
        CharacterJournal reopened = new CharacterJournal(path);
        assertArrayEquals(new int[]{3, 1, 2, 1, 0, 0, 0, 0}, reopened.accountCopy(ACCOUNT).vaultPotions);
        assertEquals("v4 values survive", 5, reopened.accountCopy(ACCOUNT).exalts.get(782)[0]);
        Path newerPath = temp.newFolder().toPath().resolve("journal.json"); String newer = v3().replace("\"version\":3", "\"version\":6");
        write(newerPath, newer);
        CharacterJournal later = new CharacterJournal(newerPath);
        assertFalse(later.readable()); assertTrue(later.storageStatus().contains("Original preserved"));
        later.vaultPotions(ACCOUNT, new int[8], 1_000); later.save();
        assertEquals(newer, read(newerPath));
    }

    @Test public void malformedNewFieldsAreUnknownAndTheJournalStaysWritable() throws Exception {
        Path path = file();
        write(path, v3().replace("\"version\":3", "\"version\":5")
            .replace("\"source\":\"Captured character\"}", "\"source\":\"Captured character\",\"pet\":{\"instanceId\":42,\"abilityType\":[1,2]},"
                + "\"dungeonCompletions\":{\"Not a dungeon\":3},\"dungeonCompletionsObservedAt\":\"soon\",\"exp\":-5,\"hasBackpack\":true}")
            .replace("\"exaltSeen\":300", "\"exaltSeen\":300,\"exaltSeenByClass\":{\"wizard\":5},\"vaultPotions\":[1,2,3]"));
        CharacterJournal j = new CharacterJournal(path);
        assertTrue("One malformed optional field never makes the journal read-only", j.readable());
        CharacterJournal.CharacterRecord r = j.characterCopy(KEY);
        assertNull(r.pet); assertNull(r.dungeonCompletions); assertEquals(0, r.dungeonCompletionsObservedAt); assertNull(r.exp);
        assertEquals("A well-formed field beside them is kept", Boolean.TRUE, r.hasBackpack);
        CharacterJournal.AccountRecord a = j.accountCopy(ACCOUNT);
        assertTrue(a.exaltSeenByClass.isEmpty()); assertNull(a.vaultPotions); assertEquals(5, a.exalts.get(782)[0]);
        j.notes(KEY, "still writable"); j.save();
        assertTrue(read(path).contains("still writable")); assertTrue(read(path).contains("\"version\": 5"));
    }

    @Test public void theCharacterListFillsPetCompletionsExperienceAndBackpack() {
        CharacterJournal j = new CharacterJournal(file());
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        CharacterJournal.CharacterRecord r = j.characterCopy(KEY);
        assertEquals(Long.valueOf(30_000), r.exp); assertEquals(Boolean.TRUE, r.hasBackpack);
        assertEquals(Map.of("Pirate Cave", 3), r.dungeonCompletions); assertEquals(5_000, r.dungeonCompletionsObservedAt);
        assertNotNull("Supplied fields keep their provenance", r.fields.get("dungeons"));
        CharacterJournal.PetRecord pet = r.pet;
        assertEquals(Long.valueOf(42), pet.instanceId); assertEquals("Pup", pet.name); assertEquals(Integer.valueOf(3), pet.type);
        assertEquals(Integer.valueOf(2), pet.rarity); assertEquals(Integer.valueOf(100), pet.skin); assertEquals(Integer.valueOf(70), pet.maxAbilityPower);
        assertNull("The character list has no family", pet.family);
        assertArrayEquals(new int[]{407, 408, 406}, pet.abilityType); assertArrayEquals(new int[]{50, 40, 30}, pet.abilityLevel);
        assertArrayEquals(new int[]{1000, 800, 600}, pet.abilityPoints); assertEquals(5_000, pet.observedAt); assertEquals("Character list", pet.source);
        RealmCharacter bare = new RealmCharacter(); bare.charId = 7; bare.receivedAt = 6_000;
        j.mergeRoster(ACCOUNT, List.of(bare));
        r = j.characterCopy(KEY);
        assertEquals("Fields a list does not report keep their values", Long.valueOf(30_000), r.exp);
        assertNotNull(r.pet); assertEquals(Map.of("Pirate Cave", 3), r.dungeonCompletions);
        j.mergeRoster(ACCOUNT, List.of(listed(4_000, 1)));
        assertEquals("An older list never replaces newer completions", Map.of("Pirate Cave", 3), j.characterCopy(KEY).dungeonCompletions);
        assertEquals("nor a newer pet", 5_000, j.characterCopy(KEY).pet.observedAt);
    }

    @Test public void completionsExaltTimesVaultPotionsAndYardPetsAreRecordedOnlyWhenValidAndNew() {
        CharacterJournal j = new CharacterJournal(file());
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        j.dungeonCompletions(ACCOUNT, 7, counts(4), 6_000);
        assertEquals(Map.of("Pirate Cave", 4), j.characterCopy(KEY).dungeonCompletions);
        long revision = j.revision();
        j.dungeonCompletions(ACCOUNT, 7, counts(9), 5_500);   // older than what is known
        j.dungeonCompletions(ACCOUNT, 7, new int[3], 7_000);   // not one count per dungeon
        j.dungeonCompletions(ACCOUNT, 8, counts(9), 7_000);    // no such character
        j.dungeonCompletions(ACCOUNT, 7, counts(4), 6_000);    // nothing new
        assertEquals(revision, j.revision());
        long before = System.currentTimeMillis();
        j.exalts(ACCOUNT, Map.of(782, new int[]{0, 0, 0, 0, 0, 0, 0, 30}, 784, new int[]{1, 1, 1, 1, 1, 1, 1, 1}));
        Map<Integer, Long> seen = j.accountCopy(ACCOUNT).exaltSeenByClass;
        assertEquals(Set.of(782, 784), seen.keySet()); assertTrue(seen.get(782) >= before);
        revision = j.revision();
        j.exalts(ACCOUNT, Map.of(782, new int[]{0, 0, 0, 0, 0, 0, 0, 30}));
        assertEquals("Unchanged counts are not stamped again", revision, j.revision());
        j.vaultPotions(ACCOUNT, new int[]{3, 1, 2, 1, 0, 0, 0, 0}, 8_000);
        j.vaultPotions(ACCOUNT, new int[]{9, 9, 9, 9, 9, 9, 9, 9}, 7_000);    // older
        j.vaultPotions(ACCOUNT, new int[]{1, 2, 3}, 9_000);                  // not 8 stats
        j.vaultPotions(ACCOUNT, new int[]{-1, 0, 0, 0, 0, 0, 0, 0}, 9_000);  // negative
        assertArrayEquals(new int[]{3, 1, 2, 1, 0, 0, 0, 0}, j.accountCopy(ACCOUNT).vaultPotions);
        assertEquals(8_000, j.accountCopy(ACCOUNT).vaultPotionsObservedAt);

        CharacterJournal.PetRecord other = new CharacterJournal.PetRecord(); other.instanceId = 43L; other.family = 1; other.observedAt = 9_000;
        j.yardPet(ACCOUNT, other);
        assertNull("Another pet changes nothing", j.characterCopy(KEY).pet.family);
        CharacterJournal.PetRecord yard = new CharacterJournal.PetRecord();
        yard.instanceId = 42L; yard.family = 4; yard.rarity = 3; yard.abilityLevel = new int[]{55, -1, -1}; yard.observedAt = 9_000; yard.source = "Pet Yard capture";
        j.yardPet(CharacterJournal.accountKey("someone-else"), yard);
        assertNull("Another account's characters are not matched", j.characterCopy(KEY).pet.family);
        j.yardPet(ACCOUNT, yard);
        CharacterJournal.PetRecord pet = j.characterCopy(KEY).pet;
        assertEquals(Integer.valueOf(4), pet.family); assertEquals(Integer.valueOf(3), pet.rarity); assertEquals("Unreported values stay", "Pup", pet.name);
        assertArrayEquals(new int[]{55, 40, 30}, pet.abilityLevel); assertEquals(9_000, pet.observedAt); assertEquals("Pet Yard capture", pet.source);
        revision = j.revision();
        yard.observedAt = 10_000; j.yardPet(ACCOUNT, yard);
        assertEquals("The same values seen again do not dirty the journal", revision, j.revision());
        j.mergeRoster(ACCOUNT, List.of(listed(11_000, 3)));
        assertEquals("A newer list keeps the family of the same pet", Integer.valueOf(4), j.characterCopy(KEY).pet.family);
    }

    @Test public void anExplicitlyEmptyPetIsSavedAsNoPetAndAMissingOneStaysUnknown() throws Exception {
        Path path = file();
        CharacterJournal j = new CharacterJournal(path);
        RealmCharacter bare = new RealmCharacter(); bare.charId = 7; bare.receivedAt = 4_000;
        j.mergeRoster(ACCOUNT, List.of(bare));
        assertNull("A list that says nothing about a pet leaves it unknown", j.characterCopy(KEY).pet);
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        RealmCharacter none = new RealmCharacter(); none.charId = 7; none.receivedAt = 6_000; none.supplied("pet.none", 6_000, "Character list");
        j.mergeRoster(ACCOUNT, List.of(none));
        CharacterJournal.PetRecord pet = j.characterCopy(KEY).pet;
        assertEquals("An explicitly empty pet is known: no pet", Boolean.TRUE, pet.absent);
        assertNull(pet.instanceId); assertNull(pet.name); assertArrayEquals(new int[]{-1, -1, -1}, pet.abilityType);
        assertEquals(6_000, pet.observedAt); assertEquals("Character list", pet.source);
        CharacterJournal.PetRecord yard = new CharacterJournal.PetRecord(); yard.instanceId = 42L; yard.family = 4; yard.observedAt = 7_000;
        j.yardPet(ACCOUNT, yard);
        assertEquals("The Pet Yard never fills in a character without a pet", Boolean.TRUE, j.characterCopy(KEY).pet.absent);
        j.save();
        assertTrue(read(path).contains("\"absent\": true"));
        assertEquals(Boolean.TRUE, new CharacterJournal(path).characterCopy(KEY).pet.absent);
        j.mergeRoster(ACCOUNT, List.of(listed(8_000, 3)));
        assertNull("A newer list with a pet replaces the absence", j.characterCopy(KEY).pet.absent);
        assertEquals("Pup", j.characterCopy(KEY).pet.name);
        Path contradictory = temp.newFolder().toPath().resolve("journal.json");
        write(contradictory, v3().replace("\"version\":3", "\"version\":5")
            .replace("\"source\":\"Captured character\"}", "\"source\":\"Captured character\",\"pet\":{\"absent\":true,\"instanceId\":42}}"));
        CharacterJournal odd = new CharacterJournal(contradictory);
        assertTrue(odd.readable()); assertNull("No pet and a pet at once is unknown, never a load failure", odd.characterCopy(KEY).pet);
    }

    @Test public void theFirstVersionFiveSaveKeepsOneBackupOfTheOlderFile() throws Exception {
        Path path = file(), backup = path.resolveSibling("journal.v4.bak");
        String v4 = v3().replace("\"version\":3", "\"version\":4");
        write(path, v4);
        CharacterJournal j = new CharacterJournal(path);
        assertFalse("Loading alone writes nothing", Files.exists(backup));
        j.notes(KEY, "first"); j.save();
        assertEquals("The older file is kept once, byte for byte", v4, read(backup));
        assertTrue(read(path).contains("\"version\": 5"));
        j.notes(KEY, "second"); j.save();
        assertEquals("Later saves never replace the backup", v4, read(backup));
        Files.delete(backup);
        CharacterJournal five = new CharacterJournal(path); five.notes(KEY, "third"); five.save();
        assertFalse("A version 5 file needs no backup", Files.exists(backup));
        Path other = temp.newFolder().toPath().resolve("journal.json"), kept = other.resolveSibling("journal.v4.bak");
        write(other, v4); write(kept, "an earlier backup");
        CharacterJournal again = new CharacterJournal(other); again.notes(KEY, "fourth"); again.save();
        assertEquals("An existing backup is never overwritten", "an earlier backup", read(kept));
        assertTrue(read(other).contains("fourth"));
    }

    /**
     * A directory (or any non-regular file) already sitting at the backup path must not count as "already backed up": that
     * would let the version 5 write proceed with no real backup ever made. Files.copy is made to fail (a directory already
     * occupies the target), which must fail the whole save, leaving the old (pre-version-5) file untouched.
     */
    @Test public void aBackupThatCannotBeWrittenNeverLetsTheVersionFiveWriteHappen() throws Exception {
        Path path = file(), backup = path.resolveSibling("journal.v4.bak");
        String v4 = v3().replace("\"version\":3", "\"version\":4");
        write(path, v4);
        Files.createDirectory(backup); // an invalid, unfinished-looking backup: not a regular file
        CharacterJournal j = new CharacterJournal(path);
        j.notes(KEY, "should never reach disk");
        j.save();
        assertTrue("The invalid backup is left alone, not silently accepted", Files.isDirectory(backup));
        assertEquals("The old file is untouched: the version 5 write never happened", v4, read(path));
        assertTrue(j.storageProblem(), j.storageProblem().startsWith("Save failed"));
    }

    @Test public void storageProblemsNameAnUnreadableFileAndAFailedSave() throws Exception {
        Path path = file(); write(path, "{broken");
        CharacterJournal broken = new CharacterJournal(path);
        assertFalse(broken.readable()); assertTrue(broken.storageProblem().contains("Cannot read"));
        CharacterJournal failing = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"),
            (target, json) -> { throw new java.io.IOException("Synthetic save failure"); });
        assertNull("Readable and never failed: no problem", failing.storageProblem());
        failing.mergeRoster(ACCOUNT, List.of(listed(5_000, 3))); failing.save();
        assertTrue(failing.storageProblem(), failing.storageProblem().startsWith("Save failed"));
    }

    @Test public void copiesAreDeepAndEveryNewFieldSurvivesSaveAndReload() throws Exception {
        Path path = file();
        CharacterJournal j = new CharacterJournal(path);
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        j.exalts(ACCOUNT, Map.of(782, new int[]{0, 0, 0, 0, 0, 0, 0, 30}));
        j.vaultPotions(ACCOUNT, new int[]{3, 1, 2, 1, 0, 0, 0, 0}, 8_000);
        CharacterJournal.CharacterRecord copy = j.characterCopy(KEY);
        copy.pet.abilityType[0] = 1; copy.pet.name = "Changed"; copy.dungeonCompletions.put("Lost Halls", 9);
        CharacterJournal.AccountRecord account = j.accountCopy(ACCOUNT);
        account.vaultPotions[0] = 99; account.exaltSeenByClass.put(1, 1L);
        assertEquals(407, j.characterCopy(KEY).pet.abilityType[0]); assertEquals("Pup", j.characterCopy(KEY).pet.name);
        assertEquals(Map.of("Pirate Cave", 3), j.characterCopy(KEY).dungeonCompletions);
        assertEquals(3, j.accountCopy(ACCOUNT).vaultPotions[0]); assertFalse(j.accountCopy(ACCOUNT).exaltSeenByClass.containsKey(1));
        j.save();
        CharacterJournal.CharacterRecord r = new CharacterJournal(path).characterCopy(KEY);
        CharacterJournal.AccountRecord a = new CharacterJournal(path).accountCopy(ACCOUNT);
        assertEquals(Long.valueOf(30_000), r.exp); assertEquals(Boolean.TRUE, r.hasBackpack);
        assertEquals(Map.of("Pirate Cave", 3), r.dungeonCompletions); assertEquals(5_000, r.dungeonCompletionsObservedAt);
        assertEquals("Pup", r.pet.name); assertEquals(Long.valueOf(42), r.pet.instanceId); assertArrayEquals(new int[]{50, 40, 30}, r.pet.abilityLevel);
        assertEquals(5_000, r.pet.observedAt); assertEquals("Character list", r.pet.source);
        assertArrayEquals(new int[]{3, 1, 2, 1, 0, 0, 0, 0}, a.vaultPotions); assertEquals(8_000, a.vaultPotionsObservedAt);
        assertTrue(a.exaltSeenByClass.containsKey(782));
    }
}
