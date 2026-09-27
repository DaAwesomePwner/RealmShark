package tomato.gui.character;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.ProgressionData;
import tomato.gui.glance.character.PetSummary;
import static org.junit.Assert.*;
import static tomato.gui.character.PetFixtures.*;

/**
 * The Pets gallery model: the account's equipped pets (one card per instance id, newest values, every character that carries it)
 * plus the Pet Yard pets seen this visit, merged by instance id; other accounts, "No pet" and unknown pets never become cards.
 */
public class PetGalleryModelTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final PetDefinitions LOADING = PetDefinitions.loading();

    private static List<String> keys(List<PetGalleryModel.PetCard> cards) {
        List<String> keys = new ArrayList<>(); for (PetGalleryModel.PetCard card : cards) keys.add(card.key()); return keys;
    }
    private static List<Integer> levels(PetSummary pet) {
        List<Integer> levels = new ArrayList<>(); for (PetSummary.Ability a : pet.abilities()) levels.add(a.level()); return levels;
    }
    private static List<Integer> points(PetSummary pet) {
        List<Integer> points = new ArrayList<>(); for (PetSummary.Ability a : pet.abilities()) points.add(a.points()); return points;
    }

    @Test public void anEquippedPetIsOneCardPerInstanceIdWithTheNewestValuesAndEveryCharacterCarryingIt() throws Exception {
        CharacterJournal.PetRecord older = rex(NOW - 60_000), newer = rex(NOW);
        older.abilityLevel = new int[]{40, 25, 1}; older.name = "Rex (old)";
        List<CharacterJournal.CharacterRecord> records = List.of(
            character(ACCOUNT, 8, KNIGHT, "Knight", false, newer),
            character(ACCOUNT, 7, WIZARD, "Wizard", false, older));
        PetDefinitions defs = defs(temp.newFolder().toPath());
        List<PetGalleryModel.PetCard> cards = PetGalleryModel.build(records, ACCOUNT, List.of(), defs);
        assertEquals(1, cards.size());
        PetGalleryModel.PetCard card = cards.get(0);
        assertEquals("pet:9001", card.key());
        assertEquals("The newest observation supplies the values", "Rex", card.pet().title());
        assertEquals(Arrays.asList(45, 30, 1), levels(card.pet()));
        assertEquals("Rare", card.pet().rarity());
        assertEquals("Family comes from pets.xml by the pet's type", "Canine", card.pet().family());
        assertEquals("Every character carrying it, by character id", List.of("Wizard #7", "Knight #8"), card.equippedBy());
        assertFalse(card.inYard());
        assertEquals(NOW, card.seenAt());
        assertEquals("Equal inputs build equal cards", cards, PetGalleryModel.build(records, ACCOUNT, List.of(), defs));
    }

    @Test public void aDeadCharacterStillCountsAndIsLabeledDead() {
        List<CharacterJournal.CharacterRecord> records = List.of(
            character(ACCOUNT, 9, PRIEST, "Priest", true, rex(NOW - 1_000)),
            character(ACCOUNT, 7, WIZARD, "Wizard", false, rex(NOW)));
        PetGalleryModel.PetCard card = PetGalleryModel.build(records, ACCOUNT, List.of(), LOADING).get(0);
        assertEquals("The living come first, then the dead", List.of("Wizard #7", "Priest #9 (dead)"), card.equippedBy());
        assertEquals("A class name the journal lacks falls back to the class id",
            List.of("Class 1234 #3"), PetGalleryModel.build(List.of(character(ACCOUNT, 3, 1234, null, false, rex(NOW))), ACCOUNT, List.of(), LOADING)
                .get(0).equippedBy());
    }

    @Test public void aYardPetMergesIntoItsEquippedCardAndNewerValuesWin() {
        CharacterJournal.PetRecord seen = pet(9001L, "Rex", HOUND, 2, 70, NOW, new int[]{HEAL, -1, -1}, new int[]{46, -1, -1}, new int[]{-1, -1, -1});
        List<PetGalleryModel.PetCard> cards = PetGalleryModel.build(List.of(character(ACCOUNT, 7, WIZARD, "Wizard", false, rex(NOW - 60_000))),
            ACCOUNT, List.of(yard(5, seen)), LOADING);
        assertEquals(1, cards.size());
        PetGalleryModel.PetCard card = cards.get(0);
        assertEquals("pet:9001", card.key());
        assertTrue(card.inYard());
        assertEquals(List.of("Wizard #7"), card.equippedBy());
        assertEquals("The yard's newer level wins; values it did not report keep the journal's", Arrays.asList(46, 30, 1), levels(card.pet()));
        assertEquals(Arrays.asList(1000, 200, 0), points(card.pet()));
        assertEquals(NOW, card.seenAt());
        assertEquals("Pet Yard capture", card.pet().source());

        CharacterJournal.PetRecord stale = pet(9001L, "Rex", HOUND, 2, 70, NOW - 120_000, new int[]{HEAL, -1, -1}, new int[]{40, -1, -1}, new int[]{-1, -1, -1});
        PetGalleryModel.PetCard older = PetGalleryModel.build(List.of(character(ACCOUNT, 7, WIZARD, "Wizard", false, rex(NOW - 60_000))),
            ACCOUNT, List.of(yard(5, stale)), LOADING).get(0);
        assertTrue("Still in the yard now", older.inYard());
        assertEquals("An older yard reading never replaces newer journal values", Arrays.asList(45, 30, 1), levels(older.pet()));
        assertEquals(NOW - 60_000, older.seenAt());
    }

    @Test public void aYardOnlyPetGetsItsOwnCardAndOneWithoutAnInstanceIdIsKeyedByItsObjectId() {
        CharacterJournal.PetRecord unequipped = pet(555L, "Tom", CAT, 1, 50, NOW, new int[]{HEAL, MAGIC_HEAL, ELECTRIC}, new int[]{20, 10, 1}, new int[]{0, 0, 0});
        CharacterJournal.PetRecord anonymous = pet(null, null, null, null, 30, NOW, new int[]{-1, -1, -1}, new int[]{1, -1, -1}, new int[]{-1, -1, -1});
        CharacterJournal.PetRecord another = pet(null, null, null, null, 30, NOW, new int[]{-1, -1, -1}, new int[]{2, -1, -1}, new int[]{-1, -1, -1});
        List<PetGalleryModel.PetCard> cards = PetGalleryModel.build(List.of(character(ACCOUNT, 7, WIZARD, "Wizard", false, rex(NOW))),
            ACCOUNT, List.of(yard(5, unequipped), yard(77, anonymous), yard(78, another)), LOADING);
        assertEquals("Yard pets first (max level, then name), then equipped",
            List.of("pet:555", "object:77", "object:78", "pet:9001"), keys(cards));
        assertTrue(cards.get(0).inYard());
        assertEquals(List.of(), cards.get(0).equippedBy());
        assertEquals("A pet without an instance id is never merged", Integer.valueOf(1), cards.get(1).pet().abilities().get(0).level());
        assertEquals(Integer.valueOf(2), cards.get(2).pet().abilities().get(0).level());
        assertNull(cards.get(1).pet().instanceId());
        assertFalse(cards.get(3).inYard());
    }

    @Test public void anotherAccountsPetsAndNoPetOrUnknownPetsAreNotCards() {
        CharacterJournal.PetRecord noInstance = rex(NOW); noInstance.instanceId = null;
        List<CharacterJournal.CharacterRecord> records = List.of(
            character(OTHER, 7, WIZARD, "Wizard", false, pet(1L, "Theirs", HOUND, 4, 100, NOW, new int[]{HEAL, HEAL, HEAL}, new int[]{1, 1, 1}, new int[]{0, 0, 0})),
            character(ACCOUNT, 1, WIZARD, "Wizard", false, null),
            character(ACCOUNT, 2, KNIGHT, "Knight", false, none(NOW)),
            character(ACCOUNT, 3, PRIEST, "Priest", false, noInstance));
        assertEquals("Unknown (null), No pet (absent) and a pet without an instance id stay off the gallery",
            List.of(), PetGalleryModel.build(records, ACCOUNT, List.of(), LOADING));
        assertEquals("No account: no equipped pets", List.of(), PetGalleryModel.build(records, null, List.of(), LOADING));
        assertEquals("Each account sees only its own pets", List.of("pet:1"), keys(PetGalleryModel.build(records, OTHER, List.of(), LOADING)));
    }

    @Test public void cardsAreOrderedYardFirstThenEquippedEachByMaxLevelThenName() {
        List<CharacterJournal.CharacterRecord> records = List.of(
            character(ACCOUNT, 1, WIZARD, "Wizard", false, pet(1L, "Bea", HOUND, 1, 50, NOW, new int[]{HEAL, -1, -1}, new int[]{1, -1, -1}, new int[]{0, -1, -1})),
            character(ACCOUNT, 2, WIZARD, "Wizard", false, pet(2L, "Ace", HOUND, 1, 50, NOW, new int[]{HEAL, -1, -1}, new int[]{1, -1, -1}, new int[]{0, -1, -1})),
            character(ACCOUNT, 3, WIZARD, "Wizard", false, pet(3L, "Zed", HOUND, 3, 90, NOW, new int[]{HEAL, -1, -1}, new int[]{1, -1, -1}, new int[]{0, -1, -1})),
            character(ACCOUNT, 4, WIZARD, "Wizard", false, pet(4L, "Unknown max", HOUND, null, null, NOW, new int[]{-1, -1, -1}, new int[]{-1, -1, -1}, new int[]{-1, -1, -1})));
        List<PetGalleryModel.YardPet> yard = List.of(
            yard(10, pet(10L, "Low", CAT, 0, 30, NOW, new int[]{HEAL, -1, -1}, new int[]{1, -1, -1}, new int[]{0, -1, -1})),
            yard(11, pet(11L, "High", CAT, 4, 100, NOW, new int[]{HEAL, -1, -1}, new int[]{1, -1, -1}, new int[]{0, -1, -1})));
        assertEquals(List.of("pet:11", "pet:10", "pet:3", "pet:2", "pet:1", "pet:4"), keys(PetGalleryModel.build(records, ACCOUNT, yard, LOADING)));
    }

    @Test public void theYardIsThisAccountsPetYardObjectsConvertedLikeTheJournalDoes() {
        ProgressionData source = new ProgressionData();
        source.reset(ACCOUNT, "Pet Yard visit");
        publish(source, 5, NOW, "Pet Yard capture", stat(StatType.PET_INSTANCE_ID_STAT, 9001), text(StatType.PET_NAME_STAT, "Rex"),
            stat(StatType.PET_TYPE_STAT, HOUND), stat(StatType.PET_RARITY_STAT, 2), stat(StatType.PET_FAMILY_STAT, 4),
            stat(StatType.PET_MAX_ABILITY_POWER_STAT, 70), stat(StatType.SKIN_ID, 0x7b01),
            stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, HEAL), stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 45), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 1000),
            stat(StatType.PET_SECOND_ABILITY_POWER_STAT, -3));
        publish(source, 6, NOW - 5, "Pet Yard capture", stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30));
        // The character list's equipped pet (a negative metadata object id) is not a Pet Yard sighting: the journal holds it.
        publish(source, -1, NOW, "Equipped character metadata", stat(StatType.PET_INSTANCE_ID_STAT, 7777));

        List<PetGalleryModel.YardPet> yard = PetGalleryModel.yard(source.snapshot(), ACCOUNT);
        assertEquals(2, yard.size());
        PetGalleryModel.YardPet rex = yard.get(0), anonymous = yard.get(1);
        assertEquals(5, rex.objectId());
        CharacterJournal.PetRecord pet = rex.pet();
        assertEquals(Long.valueOf(9001), pet.instanceId); assertEquals("Rex", pet.name); assertEquals(Integer.valueOf(HOUND), pet.type);
        assertEquals(Integer.valueOf(2), pet.rarity); assertEquals(Integer.valueOf(4), pet.family); assertEquals(Integer.valueOf(70), pet.maxAbilityPower);
        assertEquals(Integer.valueOf(0x7b01), pet.skin);
        assertArrayEquals(new int[]{HEAL, -1, -1}, pet.abilityType);
        assertArrayEquals("A negative value is unknown (-1), as TomatoData.yardPetRecord keeps it", new int[]{45, -1, -1}, pet.abilityLevel);
        assertArrayEquals(new int[]{1000, -1, -1}, pet.abilityPoints);
        assertNull(pet.absent);
        assertEquals(NOW, pet.observedAt); assertEquals("Pet Yard capture", pet.source);
        assertEquals(6, anonymous.objectId());
        assertNull("Unlike the journal's conversion, a pet without an instance id still gets a card", anonymous.pet().instanceId);

        assertEquals("Another account's yard is never shown", List.of(), PetGalleryModel.yard(source.snapshot(), OTHER));
        assertEquals(List.of(), PetGalleryModel.yard(source.snapshot(), null));
        source.reset(ACCOUNT, "Left the Pet Yard");
        assertEquals("Leaving the yard clears it", List.of(), PetGalleryModel.yard(source.snapshot(), ACCOUNT));
    }

    @Test public void theCurrentAccountIsTheLiveOneThenTheLastKnownThenTheMostRecentCharacters() {
        LiveCharacter.Snapshot live = snapshot(ACCOUNT), known = snapshot(OTHER);
        CharacterJournal.CharacterRecord recent = character(CharacterJournal.accountKey("recent"), 1, WIZARD, "Wizard", false, null);
        assertEquals(ACCOUNT, PetGalleryModel.account(live, known, recent));
        assertEquals(OTHER, PetGalleryModel.account(null, known, recent));
        assertEquals(recent.account, PetGalleryModel.account(null, null, recent));
        assertNull(PetGalleryModel.account(null, null, null));
    }

    private static LiveCharacter.Snapshot snapshot(String account) {
        return new LiveCharacter.Snapshot(account, 7, WIZARD, "Sample", null, 20, 100L, null, null, null, null, null, null, null, null, NOW);
    }
}
