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

    @Test public void legacyFilesWithoutEquipmentEnchantsLeaveThemNotRecorded() throws Exception {
        for (int version : new int[] {4, 5}) {
            Path path = temp.newFolder().toPath().resolve("journal.json");
            write(path, v3().replace("\"version\":3", "\"version\":" + version));
            CharacterJournal j = new CharacterJournal(path);
            assertTrue(j.readable());
            CharacterJournal.CharacterRecord r = j.characterCopy(KEY);
            assertNull(r.equipmentEnchants); assertNull(r.enchantInfos());
        }
    }

    @Test public void malformedEquipmentEnchantsAreUnknownAndTheJournalStaysWritable() throws Exception {
        for (String malformed : new String[] {"12", "[\"a\",\"b\"]"}) {
            Path path = temp.newFolder().toPath().resolve("journal.json");
            write(path, v3().replace("\"version\":3", "\"version\":5")
                .replace("\"source\":\"Captured character\"}",
                    "\"source\":\"Captured character\",\"equipmentEnchants\":" + malformed + "}"));
            CharacterJournal j = new CharacterJournal(path);
            assertTrue("One malformed optional field never makes the journal read-only", j.readable());
            assertNull(j.characterCopy(KEY).equipmentEnchants);
            assertNull(j.characterCopy(KEY).enchantInfos());
            j.notes(KEY, "still writable"); j.save();
            assertTrue(read(path).contains("still writable")); assertTrue(read(path).contains("\"version\": 5"));
            assertNull(new CharacterJournal(path).characterCopy(KEY).equipmentEnchants);
        }
    }

    @Test public void equipmentEnchantsCopiesAreDeepAndSurviveSaveAndReload() throws Exception {
        Path path = file();
        write(path, v3().replace("\"version\":3", "\"version\":5")
            .replace("\"source\":\"Captured character\"}",
                "\"source\":\"Captured character\",\"equipmentEnchants\":[\"AAIE_wU\",\"\",null,null]}"));
        CharacterJournal j = new CharacterJournal(path);
        CharacterJournal.CharacterRecord copy = j.characterCopy(KEY);
        assertNotSame(copy.equipmentEnchants, j.characterCopy(KEY).equipmentEnchants);
        copy.equipmentEnchants[0] = "Changed";
        String[] expected = {"AAIE_wU", "", null, null};
        assertArrayEquals(expected, j.characterCopy(KEY).equipmentEnchants);
        j.notes(KEY, "save enchant snapshot"); j.save();
        assertArrayEquals(expected, new CharacterJournal(path).characterCopy(KEY).equipmentEnchants);
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

    @Test public void anOlderPetYardObservationNeverReplacesANewerPet() {
        CharacterJournal j = new CharacterJournal(file());
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        CharacterJournal.PetRecord yard = new CharacterJournal.PetRecord();
        yard.instanceId = 42L; yard.family = 4; yard.rarity = 3; yard.abilityLevel = new int[]{55, -1, -1}; yard.observedAt = 9_000;
        j.yardPet(ACCOUNT, yard);
        long revision = j.revision();
        CharacterJournal.PetRecord stale = new CharacterJournal.PetRecord();
        stale.instanceId = 42L; stale.family = 2; stale.rarity = 1; stale.name = "Old"; stale.abilityLevel = new int[]{10, 10, 10}; stale.observedAt = 8_000;
        j.yardPet(ACCOUNT, stale);
        CharacterJournal.PetRecord pet = j.characterCopy(KEY).pet;
        assertEquals("An older Pet Yard snapshot changes nothing", revision, j.revision());
        assertEquals(Integer.valueOf(4), pet.family); assertEquals(Integer.valueOf(3), pet.rarity); assertEquals("Pup", pet.name);
        assertArrayEquals(new int[]{55, 40, 30}, pet.abilityLevel); assertEquals(9_000, pet.observedAt);
        j.mergeRoster(ACCOUNT, List.of(listed(9_500, 3)));
        revision = j.revision();
        j.yardPet(ACCOUNT, stale);
        pet = j.characterCopy(KEY).pet;
        assertEquals("nor one older than the list's pet", revision, j.revision());
        assertEquals(Integer.valueOf(2), pet.rarity); assertEquals(Integer.valueOf(4), pet.family); assertEquals(9_500, pet.observedAt);
    }

    @Test public void aPartialListEntryForTheSamePetKeepsItsOmittedValues() {
        CharacterJournal j = new CharacterJournal(file());
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        CharacterJournal.PetRecord yard = new CharacterJournal.PetRecord(); yard.instanceId = 42L; yard.family = 4; yard.observedAt = 6_000;
        j.yardPet(ACCOUNT, yard);
        RealmCharacter partial = new RealmCharacter(); partial.charId = 7; partial.receivedAt = 7_000;
        partial.petInstanceId = 42; partial.petRarity = 3; partial.petAbilitys = new int[]{0, 60, 0, 0, 0, 0, 0, 0, 0};
        for (int field : new int[]{81, 84, 90}) partial.supplied("pet." + field, 7_000, "Character list");
        j.mergeRoster(ACCOUNT, List.of(partial));
        CharacterJournal.PetRecord pet = j.characterCopy(KEY).pet;
        assertEquals("Reported values replace", Integer.valueOf(3), pet.rarity); assertArrayEquals(new int[]{60, 40, 30}, pet.abilityLevel);
        assertEquals("Omitted values of the same pet stay", "Pup", pet.name); assertEquals(Integer.valueOf(3), pet.type);
        assertEquals(Integer.valueOf(100), pet.skin); assertEquals(Integer.valueOf(70), pet.maxAbilityPower); assertEquals(Integer.valueOf(4), pet.family);
        assertArrayEquals(new int[]{407, 408, 406}, pet.abilityType); assertArrayEquals(new int[]{1000, 800, 600}, pet.abilityPoints);
        assertEquals(7_000, pet.observedAt); assertEquals("Character list", pet.source);

        RealmCharacter other = new RealmCharacter(); other.charId = 7; other.receivedAt = 8_000;
        other.petInstanceId = 43; other.petName = "Kit";
        for (int field : new int[]{81, 82}) other.supplied("pet." + field, 8_000, "Character list");
        j.mergeRoster(ACCOUNT, List.of(other));
        pet = j.characterCopy(KEY).pet;
        assertEquals(Long.valueOf(43), pet.instanceId); assertEquals("Kit", pet.name);
        assertNull("A different pet keeps nothing of the old one", pet.rarity); assertNull(pet.family); assertNull(pet.type);
        assertArrayEquals(new int[]{-1, -1, -1}, pet.abilityLevel);

        RealmCharacter unidentified = new RealmCharacter(); unidentified.charId = 7; unidentified.receivedAt = 9_000; unidentified.petName = "Kit";
        unidentified.supplied("pet.82", 9_000, "Character list");
        j.mergeRoster(ACCOUNT, List.of(unidentified));
        pet = j.characterCopy(KEY).pet;
        assertNull("Without an instance id the list cannot prove it is the same pet", pet.instanceId); assertEquals("Kit", pet.name);
    }

    @Test public void anExplicitlyEmptyPetIsSavedAsNoPetAndAMissingOneStaysUnknown() throws Exception {
        Path path = file();
        CharacterJournal j = new CharacterJournal(path);
        RealmCharacter bare = new RealmCharacter(); bare.charId = 7; bare.receivedAt = 4_000;
        j.mergeRoster(ACCOUNT, List.of(bare));
        assertNull("A list that says nothing about a pet leaves it unknown", j.characterCopy(KEY).pet);
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        RealmCharacter none = new RealmCharacter(); none.charId = 7; none.receivedAt = 6_000; none.supplied(RealmCharacter.PET_NONE, 6_000, "Character list");
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
        // Names the backup path: a stuck journal.v4.bak is a different, more diagnosable problem than a plain write failure.
        assertTrue(j.storageProblem(), j.storageProblem().contains("journal.v4.bak"));
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

    /** The failure flag, not the wording, decides: the status text is replaced by reflection to prove it. */
    @Test public void aFailedSaveIsFlaggedUntilTheNextSuccessfulWrite() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        Path path = temp.newFolder().toPath().resolve("journal.json");
        CharacterJournal j = new CharacterJournal(path, (target, json) -> {
            if (fail.get()) throw new java.io.IOException("Synthetic save failure");
            Files.write(target, json.getBytes(StandardCharsets.UTF_8));
        });
        java.lang.reflect.Field status = CharacterJournal.class.getDeclaredField("storageStatus"); status.setAccessible(true);
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3))); j.save();
        assertTrue(j.readable());
        assertNotNull("A failed save is a storage problem", j.storageProblem());
        assertFalse("A failed save never reads as saving", j.storageStatus().startsWith("Saving"));
        synchronized (j) { status.set(j, "Synthetic wording without the usual prefix"); }
        assertEquals("The flag decides, not the text", "Synthetic wording without the usual prefix", j.storageProblem());
        assertEquals("A failed save never reads as saving, whatever its text", "Synthetic wording without the usual prefix", j.storageStatus());
        fail.set(false); j.save(); // dirty stays true after a failure, so the next save retries
        assertNull("The next successful write clears it", j.storageProblem());
        assertTrue(j.storageStatus(), j.storageStatus().startsWith("Saved"));
        assertTrue(read(path).contains("\"version\": 5"));
        synchronized (j) { status.set(j, "Save failed (an old message kept by mistake)"); }
        assertNull("A success is not undone by a failure-like text", j.storageProblem());
        j.notes(KEY, "pending");
        assertEquals("Unsaved changes after a success read as saving", "Saving locally…", j.storageStatus());
    }

    /**
     * A RuntimeException from the store is a failed save like an IOException: save() never throws it (the scheduled saver would be
     * cancelled for good), the status says so, and the change stays dirty so the next save retries. The backup copy has no
     * injectable seam, so only the write path is driven here.
     */
    @Test public void aRuntimeFailureInTheWriteIsAFailedSaveAndTheNextSaveRetries() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        Path path = temp.newFolder().toPath().resolve("journal.json");
        CharacterJournal j = new CharacterJournal(path, (target, json) -> {
            if (fail.getAndSet(false)) throw new java.nio.file.InvalidPathException(target.getFileName().toString(), "Synthetic runtime failure");
            Files.write(target, json.getBytes(StandardCharsets.UTF_8));
        });
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        j.save();   // must not throw
        assertTrue(j.readable());
        assertNotNull("A runtime failure is a failed save", j.storageProblem());
        assertEquals("The same text as a write IOException", "Save failed • check access to Characters/journal.json", j.storageProblem());
        assertFalse("Nothing was written", Files.exists(path));
        j.save();   // still dirty: retried
        assertNull("The retry succeeds and clears the problem", j.storageProblem());
        assertTrue(read(path).contains("\"version\": 5"));
        assertEquals(Long.valueOf(30_000), new CharacterJournal(path).characterCopy(KEY).exp);
    }

    @Test public void aNoPetRecordWithAnyPetValueLoadsAsUnknown() throws Exception {
        for (String value : new String[]{"\"skin\":100", "\"maxAbilityPower\":70", "\"abilityLevel\":[-1,5,-1]",
                "\"abilityType\":[407,-1,-1]", "\"abilityPoints\":[-1,-1,0]"}) {
            Path path = temp.newFolder().toPath().resolve("journal.json");
            write(path, v3().replace("\"version\":3", "\"version\":5").replace("\"source\":\"Captured character\"}",
                "\"source\":\"Captured character\",\"pet\":{\"absent\":true," + value + ",\"observedAt\":6000},\"hasBackpack\":true}"));
            CharacterJournal j = new CharacterJournal(path);
            assertTrue(value, j.readable());
            assertNull("No pet and " + value + " at once is unknown", j.characterCopy(KEY).pet);
            assertEquals("A neighbouring field is kept", Boolean.TRUE, j.characterCopy(KEY).hasBackpack);
            j.notes(KEY, "writable"); j.save();
            assertTrue(value, read(path).contains("\"version\": 5"));
        }
        Path plain = temp.newFolder().toPath().resolve("journal.json");
        write(plain, v3().replace("\"version\":3", "\"version\":5").replace("\"source\":\"Captured character\"}",
            "\"source\":\"Captured character\",\"pet\":{\"absent\":true,\"abilityType\":[-1,-1,-1],\"abilityLevel\":[-1,-1,-1],"
                + "\"abilityPoints\":[-1,-1,-1],\"observedAt\":6000,\"source\":\"Character list\"}}"));
        CharacterJournal.PetRecord none = new CharacterJournal(plain).characterCopy(KEY).pet;
        assertEquals("-1 abilities are unknown, not pet values: still a known no pet", Boolean.TRUE, none.absent);
        assertEquals(6_000, none.observedAt);
    }

    /** Valid v5 values for every field below; each case breaks exactly one of them. */
    private String strictDocument(String exp, String backpack, String completions, String completionsAt, String seenByClass, String potions) {
        return v3().replace("\"version\":3", "\"version\":5")
            .replace("\"source\":\"Captured character\"}", "\"source\":\"Captured character\",\"exp\":" + exp + ",\"hasBackpack\":" + backpack
                + ",\"dungeonCompletions\":" + completions + ",\"dungeonCompletionsObservedAt\":" + completionsAt + "}")
            .replace("\"exaltSeen\":300", "\"exaltSeen\":300,\"exaltSeenByClass\":" + seenByClass + ",\"vaultPotions\":" + potions
                + ",\"vaultPotionsObservedAt\":8000");
    }

    @Test public void v5FieldsParseStrictly() throws Exception {
        String exp = "123", backpack = "true", completions = "{\"Pirate Cave\":3}", at = "5000", seen = "{\"782\":7000}", potions = "[3,1,2,1,0,0,0,0]";
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("hasBackpack \"yes\"", strictDocument(exp, "\"yes\"", completions, at, seen, potions));
        cases.put("hasBackpack 1", strictDocument(exp, "1", completions, at, seen, potions));
        cases.put("exp \"123\"", strictDocument("\"123\"", backpack, completions, at, seen, potions));
        cases.put("exp 1.5", strictDocument("1.5", backpack, completions, at, seen, potions));
        cases.put("dungeonCompletionsObservedAt \"5\"", strictDocument(exp, backpack, completions, "\"5\"", seen, potions));
        cases.put("dungeonCompletions quoted count", strictDocument(exp, backpack, "{\"Pirate Cave\":\"3\"}", at, seen, potions));
        cases.put("vaultPotions int overflow", strictDocument(exp, backpack, completions, at, seen, "[1,2,3,4,5,6,7,4294967297]"));
        cases.put("vaultPotions quoted count", strictDocument(exp, backpack, completions, at, seen, "[1,\"2\",3,4,5,6,7,8]"));
        cases.put("exaltSeenByClass quoted time", strictDocument(exp, backpack, completions, at, "{\"782\":\"7\"}", potions));
        for (Map.Entry<String, String> c : cases.entrySet()) {
            Path path = temp.newFolder().toPath().resolve("journal.json");
            write(path, c.getValue());
            CharacterJournal j = new CharacterJournal(path);
            String name = c.getKey();
            assertTrue(name + ": one malformed field never makes the journal read-only", j.readable());
            CharacterJournal.CharacterRecord r = j.characterCopy(KEY);
            CharacterJournal.AccountRecord a = j.accountCopy(ACCOUNT);
            boolean field;
            field = name.startsWith("exp "); assertEquals(name, field ? null : Long.valueOf(123), r.exp);
            field = name.startsWith("hasBackpack "); assertEquals(name, field ? null : Boolean.TRUE, r.hasBackpack);
            field = name.startsWith("dungeonCompletions "); assertEquals(name, field ? null : Map.of("Pirate Cave", 3), r.dungeonCompletions);
            field = name.startsWith("dungeonCompletions"); assertEquals(name, field ? 0 : 5_000, r.dungeonCompletionsObservedAt);
            field = name.startsWith("exaltSeenByClass "); assertEquals(name, field ? Map.of() : Map.of(782, 7_000L), a.exaltSeenByClass);
            field = name.startsWith("vaultPotions "); assertArrayEquals(name, field ? null : new int[]{3, 1, 2, 1, 0, 0, 0, 0}, a.vaultPotions);
            assertEquals(name + ": pre-v5 values load", 5, a.exalts.get(782)[0]);
            assertEquals(name, "Sample", r.name);
            j.notes(KEY, "still writable"); j.save();
            assertTrue(name, read(path).contains("still writable")); assertTrue(name, read(path).contains("\"version\": 5"));
        }
        Path pet = temp.newFolder().toPath().resolve("journal.json");
        write(pet, v3().replace("\"version\":3", "\"version\":5").replace("\"source\":\"Captured character\"}",
            "\"source\":\"Captured character\",\"pet\":{\"instanceId\":42,\"rarity\":\"2\"},\"exp\":4294967297}"));
        CharacterJournal.CharacterRecord loose = new CharacterJournal(pet).characterCopy(KEY);
        assertNull("A pet with a quoted rarity is unknown", loose.pet);
        assertEquals("A long beyond the int range is a valid long", Long.valueOf(4_294_967_297L), loose.exp);
    }

    @Test public void theBackupFailureStatusExplainsRecovery() throws Exception {
        Path path = file(), backup = path.resolveSibling("journal.v4.bak");
        String v4 = v3().replace("\"version\":3", "\"version\":4");
        write(path, v4);
        Files.createDirectory(backup); // something other than a file already occupies the backup path
        CharacterJournal j = new CharacterJournal(path);
        j.notes(KEY, "kept in memory"); j.save();
        String problem = j.storageProblem();
        assertNotNull(problem);
        assertTrue(problem, problem.contains("journal.v4.bak"));
        assertTrue(problem, problem.contains("journal.json is unchanged"));
        assertTrue(problem, problem.contains("free disk space"));
        assertTrue(problem, problem.contains("retries automatically"));
        assertFalse("No absolute path in the text: " + problem, problem.contains(temp.getRoot().getAbsolutePath()));
        assertEquals("The status bar shows the same guidance", problem, j.storageStatus());
        assertEquals(v4, read(path));
        Files.delete(backup); // the user clears the path
        j.save();
        assertNull("The retry succeeds and the status clears", j.storageProblem());
        assertTrue(j.storageStatus(), j.storageStatus().startsWith("Saved"));
        assertEquals("The backup is the pre-upgrade file", v4, read(backup));
        assertTrue(read(path).contains("kept in memory")); assertTrue(read(path).contains("\"version\": 5"));
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
