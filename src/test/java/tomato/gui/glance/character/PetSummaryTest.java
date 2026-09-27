package tomato.gui.glance.character;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.PetDefinitions;
import static org.junit.Assert.*;

/** The one pet view model: unknown vs "No pet" vs a known pet, names from PetDefinitions, locked slots, content equality. */
public class PetSummaryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long NOW = 1_700_000_000_000L;
    private static final int HOUND = 0x7a01;

    /** A synthetic pets.xml naming the fixture pet type's family, read through the public loader. */
    private PetDefinitions defs() throws Exception {
        Path root = temp.newFolder().toPath(); Files.createDirectories(root.resolve("xml"));
        Files.write(root.resolve("xml/pets.xml"), ("<Objects><Object type='0x7a01' id='Fixture Hound'><Family>Canine</Family></Object></Objects>")
            .getBytes(StandardCharsets.UTF_8));
        PetDefinitions defs = PetDefinitions.read(root);
        assertTrue(defs.available);
        return defs;
    }
    private static CharacterJournal.PetRecord full() {
        CharacterJournal.PetRecord pet = new CharacterJournal.PetRecord();
        pet.instanceId = 9001L; pet.name = "Rex"; pet.type = HOUND; pet.rarity = 2; pet.family = 5; pet.skin = 0x7b01; pet.maxAbilityPower = 70;
        pet.abilityType = new int[]{407, 408, 406}; pet.abilityLevel = new int[]{45, 30, 1}; pet.abilityPoints = new int[]{1000, 200, 0};
        pet.observedAt = NOW; pet.source = "Pet Yard";
        return pet;
    }
    private static PetSummary withMax(Integer max) { CharacterJournal.PetRecord pet = full(); pet.maxAbilityPower = max; return PetSummary.of(pet, PetDefinitions.loading()); }
    private static List<Boolean> locks(PetSummary summary) {
        List<Boolean> locked = new ArrayList<>(); for (PetSummary.Ability ability : summary.abilities()) locked.add(ability.locked()); return locked;
    }
    private static void assertUnknownSlots(PetSummary summary) {
        assertEquals(3, summary.abilities().size());
        for (int i = 0; i < 3; i++) assertEquals(new PetSummary.Ability(i, null, null, null, null, false), summary.abilities().get(i));
    }

    @Test public void nullIsUnknown() throws Exception {
        PetSummary unknown = PetSummary.of(null, defs());
        assertSame(PetSummary.UNKNOWN, unknown);
        assertEquals(PetSummary.State.UNKNOWN, unknown.state());
        assertNull(unknown.instanceId()); assertNull(unknown.name()); assertNull(unknown.rarity()); assertNull(unknown.family());
        assertNull(unknown.maxLevel()); assertEquals(0, unknown.observedAt()); assertNull(unknown.source());
        assertUnknownSlots(unknown);
        assertNull("Unknown is never shown as a chip", unknown.chip()); assertEquals("Pet", unknown.title());
    }

    @Test public void absentIsNoPetAndKeepsWhenAndWhere() throws Exception {
        CharacterJournal.PetRecord none = new CharacterJournal.PetRecord(); none.absent = Boolean.TRUE; none.observedAt = NOW; none.source = "Character list";
        PetSummary summary = PetSummary.of(none, defs());
        assertEquals(PetSummary.State.NONE, summary.state());
        assertEquals("No pet", summary.chip()); assertEquals("Pet", summary.title());
        assertEquals(NOW, summary.observedAt()); assertEquals("Character list", summary.source());
        assertNull(summary.instanceId()); assertNull(summary.rarity()); assertNull(summary.family()); assertNull(summary.maxLevel());
        assertUnknownSlots(summary);
        assertNotEquals("No pet is not unknown", PetSummary.UNKNOWN, summary);
    }

    @Test public void aFullRecordIsKnownWithNamesLevelsAndPoints() throws Exception {
        PetSummary summary;
        try (AutoCloseable pin = PetDefinitions.install(defs())) { summary = PetSummary.of(full(), PetDefinitions.current()); }
        assertEquals(PetSummary.State.KNOWN, summary.state());
        assertEquals(Long.valueOf(9001L), summary.instanceId()); assertEquals("Rex", summary.name()); assertEquals("Rex", summary.title());
        assertEquals(Integer.valueOf(0x7b01), summary.skin()); assertEquals(Integer.valueOf(HOUND), summary.type());
        assertEquals(Integer.valueOf(2), summary.rarityValue()); assertEquals("Rare", summary.rarity()); assertEquals("Rare pet", summary.chip());
        assertEquals("The family name comes from pets.xml by type, never from the numeric family stat", "Canine", summary.family());
        assertEquals(Integer.valueOf(70), summary.maxLevel()); assertEquals(NOW, summary.observedAt()); assertEquals("Pet Yard", summary.source());
        assertEquals(Arrays.asList(new PetSummary.Ability(0, 407, "Heal", 45, 1000, false), new PetSummary.Ability(1, 408, "Magic heal", 30, 200, false),
            new PetSummary.Ability(2, 406, "Electric", 1, 0, true)), summary.abilities());
    }

    @Test public void minusOneValuesAndMissingNamesAreUnknown() {
        CharacterJournal.PetRecord pet = full();
        pet.abilityType = new int[]{-1, 403, 999}; pet.abilityLevel = new int[]{-1, 12, -1}; pet.abilityPoints = new int[]{-1, -1, 0};
        PetSummary summary = PetSummary.of(pet, PetDefinitions.loading());
        assertEquals(new PetSummary.Ability(0, null, null, null, null, false), summary.abilities().get(0));
        assertEquals("A captured type without a name keeps its number", new PetSummary.Ability(1, 403, "Ability #403", 12, null, false), summary.abilities().get(1));
        assertEquals("A captured zero stays zero", new PetSummary.Ability(2, 999, "Ability #999", null, 0, true), summary.abilities().get(2));
        assertNull("Family is unknown while names load", summary.family());
        assertNull(PetSummary.of(full(), null).family());
        assertNull(PetSummary.of(full(), PetDefinitions.unavailable()).family());

        CharacterJournal.PetRecord broken = full(); broken.abilityType = null; broken.abilityLevel = new int[]{5}; broken.abilityPoints = new int[]{1, 2, 3, 4};
        PetSummary tolerant = PetSummary.of(broken, PetDefinitions.loading());
        assertEquals(PetSummary.State.KNOWN, tolerant.state());
        for (PetSummary.Ability ability : tolerant.abilities()) {
            assertNull(ability.type()); assertNull(ability.name()); assertNull(ability.level()); assertNull(ability.points());
        }
        CharacterJournal.PetRecord bare = new CharacterJournal.PetRecord();
        PetSummary empty = PetSummary.of(bare, PetDefinitions.loading());
        assertEquals("A reported pet with no values is known but empty", PetSummary.State.KNOWN, empty.state());
        assertUnknownSlots(empty); assertNull(empty.chip()); assertEquals("Pet", empty.title());
    }

    @Test public void theMaxLevelLocksSlotsItHasNotUnlocked() {
        assertEquals(Arrays.asList(false, true, true), locks(withMax(30)));
        assertEquals(Arrays.asList(false, false, true), locks(withMax(50)));
        assertEquals(Arrays.asList(false, false, true), locks(withMax(70)));
        assertEquals(Arrays.asList(false, false, false), locks(withMax(90)));
        assertEquals(Arrays.asList(false, false, false), locks(withMax(100)));
        assertEquals("An unknown max level locks nothing", Arrays.asList(false, false, false), locks(withMax(null)));
    }

    @Test public void theChipIsHiddenForAnUnknownRarity() {
        CharacterJournal.PetRecord noRarity = full(); noRarity.rarity = null;
        assertNull(PetSummary.of(noRarity, PetDefinitions.loading()).chip());
        CharacterJournal.PetRecord odd = full(); odd.rarity = 7;
        PetSummary summary = PetSummary.of(odd, PetDefinitions.loading());
        assertEquals(Integer.valueOf(7), summary.rarityValue()); assertNull("Never guessed", summary.rarity()); assertNull(summary.chip());
        CharacterJournal.PetRecord legendary = full(); legendary.rarity = 3;
        assertEquals("Legendary pet", PetSummary.of(legendary, PetDefinitions.loading()).chip());
    }

    @Test public void theTitleFallsBackToPet() {
        CharacterJournal.PetRecord unnamed = full(); unnamed.name = null;
        assertEquals("Pet", PetSummary.of(unnamed, PetDefinitions.loading()).title());
        unnamed.name = "  ";
        assertEquals("Pet", PetSummary.of(unnamed, PetDefinitions.loading()).title());
    }

    @Test public void equalInputsGiveEqualImmutableSummaries() throws Exception {
        PetDefinitions defs = defs();
        CharacterJournal.PetRecord pet = full();
        PetSummary a = PetSummary.of(pet, defs), b = PetSummary.of(full(), defs());
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode());
        assertArrayEquals("The record is not changed", new int[]{45, 30, 1}, pet.abilityLevel);
        CharacterJournal.PetRecord other = full(); other.abilityLevel = new int[]{46, 30, 1};
        assertNotEquals(a, PetSummary.of(other, defs));
        try { a.abilities().add(null); fail("Abilities are immutable"); } catch (UnsupportedOperationException expected) { }

        List<PetSummary.Ability> slots = new ArrayList<>(a.abilities());
        PetSummary copy = new PetSummary(a.state(), a.instanceId(), a.name(), a.skin(), a.type(), a.rarityValue(), a.rarity(), a.family(),
            a.maxLevel(), slots, a.observedAt(), a.source());
        slots.set(0, new PetSummary.Ability(0, null, null, null, null, false));
        assertEquals("The list is copied", a, copy);
    }
}
