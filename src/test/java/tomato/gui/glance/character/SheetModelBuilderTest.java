package tomato.gui.glance.character;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.Entity;
import tomato.backend.data.FieldCapture;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.DisplayValue;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.myinfo.BuildEstimates;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Sheet rules on synthetic records: Home's potion arithmetic, vault counts only when known, unknown never 0, live only while playing. */
public class SheetModelBuilderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void aCharacterNotInGameShowsTheEnchantsLastObservedLive() {
        CharacterJournal.CharacterRecord r = SheetFixtures.record();
        r.equipmentEnchants = new String[] {"AAIE_wU", "", null, "!!!"};
        List<EnchantInfo> saved = SheetFixtures.model(r, SheetFixtures.account(), null).gear().enchants();
        assertEquals(EnchantInfo.Rarity.UNCOMMON, saved.get(0).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, saved.get(1).rarity());
        assertSame(EnchantInfo.notRecorded(), saved.get(2));
        assertSame(EnchantInfo.unreadable(), saved.get(3));
    }

    @Test public void anotherCharacterInGameDoesNotReplaceTheViewedRecordsSavedEnchants() {
        CharacterJournal.CharacterRecord r = SheetFixtures.record();
        r.equipmentEnchants = new String[] {"AAIE_wU", "", "", ""};
        Entity player = new Entity(null, 1, 0);
        StatData enchants = new StatData();
        enchants.stringStatValue = "AAIE,,,";
        player.stat.set(StatType.UNIQUE_DATA_STRING, enchants);
        BuildEstimates.Inputs inputs = BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.UNKNOWN);
        SheetModel m = SheetFixtures.model(r, SheetFixtures.account(), SheetFixtures.live(ACCOUNT, 8, "Ann", inputs));
        assertEquals(EnchantInfo.Rarity.UNCOMMON, m.gear().enchants().get(0).rarity());
    }

    @Test public void potionsMaxedAndNeedsUseCapsAndVaultCounts() {
        CharacterJournal.AccountRecord account = account();
        account.vaultPotions = new int[]{0, 0, 0, 3, 0, 0, 0, 7}; account.vaultPotionsObservedAt = NOW - 3 * HOUR;
        CharacterJournal.CharacterRecord regular = record(); regular.seasonal = Boolean.FALSE;
        SheetModel m = model(regular, account, null);
        assertEquals("The vault count keeps its time", NOW - 3 * HOUR, m.stats().vaultObservedAt());
        assertNull("Only the regular vault is recorded: a seasonal character shows no vault count", model(record(), account, null).stats().vault());
        assertEquals(KEY, m.key()); assertEquals(5, m.identity().maxed()); assertEquals(0, m.stats().unknown());
        assertEquals(List.of(720, 252, 75, 25, 50, 75, 40, 60), m.stats().caps());
        assertEquals(List.of(0, 0, 0, 5, 0, 0, 3, 12), m.stats().needed());
        assertEquals("A real zero in the vault stays zero", List.of("DEF needs 5 · 3 in vault", "VIT needs 3 · 0 in vault",
            "WIS needs 12 · 7 in vault"), m.stats().needs());
        assertEquals("Captured total minus boost · " + DisplayFormat.formatTimestamp(NOW - 2 * HOUR), m.stats().evidence().get(3));
        assertFalse(m.identity().playing()); assertEquals(Collections.nCopies(8, 0), m.stats().boosts());
        assertEquals(DisplayValue.State.STALE, m.identity().fame().state); assertEquals(NOW - 2 * HOUR, m.identity().lastSeen());
        assertEquals("Never observed in game", 0, m.identity().lastPlayed());
    }

    @Test public void unknownIsNeverShownAsZero() {
        CharacterJournal.CharacterRecord partial = record(); partial.stats[3] = null;
        SheetModel m = model(partial, account(), null);
        assertEquals(Integer.valueOf(-1), m.stats().needed().get(3));
        assertEquals(-1, m.identity().maxed()); assertEquals(1, m.stats().unknown()); assertNull(m.stats().vault());
        assertEquals("Vault counts appear only when known", List.of("VIT needs 3", "WIS needs 12"), m.stats().needs());
        assertEquals("Not captured", m.stats().evidence().get(3));
        SheetModel noCaps = SheetModelBuilder.build(record(), account(), null, RosterDefinitions.empty(), null, NOW);
        assertEquals(-1, noCaps.identity().maxed()); assertEquals(8, noCaps.stats().unknown()); assertTrue(noCaps.stats().needs().isEmpty());
        SheetModel noExalts = model(record(), null, null);
        assertFalse(noExalts.exalts().known()); assertEquals("", noExalts.exalts().summary());
        assertEquals(Collections.nCopies(8, -1), noExalts.exalts().completions());
        CharacterJournal.CharacterRecord noFame = record(); noFame.fame = null;
        assertEquals(DisplayValue.State.UNKNOWN, model(noFame, account(), null).identity().fame().state);
        assertEquals("Not captured stays -1, captured empty is 0", List.of(2_001, 0, -1, 2_004), m.gear().slots().subList(0, 4));
        assertNull("A record without saved enchants shows none", m.gear().enchants());
    }

    @Test public void exaltSummaryListsTheTopThreeByTierThenHowManyMore() {
        SheetModel.Exalts e = model(record(), account(80, 15, 30, 50, 0, 0, 0, 5), null).exalts();
        assertEquals(List.of(5, 2, 3, 4, 0, 0, 0, 1), e.tiers());
        assertEquals("LIFE 5/5 · DEF 4/5 · ATT 3/5 · +2 more", e.summary());
        assertEquals(180, e.total()); assertEquals(0, e.lowest());
        assertEquals("Ties keep stat order", "ATT 1/5 · DEF 1/5 · SPD 1/5", model(record(), account(0, 0, 5, 5, 5, 0, 0, 0), null).exalts().summary());
        assertEquals("No exalt tiers yet", model(record(), account(0, 4, 0, 0, 0, 0, 0, 0), null).exalts().summary());
    }

    @Test public void liveValuesApplyOnlyWhileThisCharacterIsPlaying() throws Exception {
        // liveRef() resolves the pointer's class name (SheetModelBuilder.className) at build time, so the fixture must be
        // installed before building any model whose live() is checked below.
        try (AutoCloseable wizard = className(WIZARD, "Wizard")) {
            SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null));
            assertTrue(playing.identity().playing()); assertEquals(NOW, playing.identity().lastSeen()); assertEquals(NOW, playing.identity().lastPlayed());
            assertEquals("Boost is total minus base", List.of(80, 48, 15, 10, 10, 5, 13, 22), playing.stats().boosts());
            assertEquals("Live equipped slots are the freshest", List.of(2_001, 0, 2_003, 2_004), playing.gear().slots().subList(0, 4));
            // The pointer is worded by class, never by name: the game's name stat is the account's, shared by every character.
            assertEquals(new SheetModel.Live(KEY, "Wizard", 7), playing.live());
            assertEquals(DisplayValue.State.KNOWN, playing.identity().fame().state);
            assertEquals("Equal inputs build equal models", playing, model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null)));
            SheetModel other = model(record(), account(), live(ACCOUNT, 8, "Ann", null));
            assertFalse(other.identity().playing()); assertEquals(Collections.nCopies(8, 0), other.stats().boosts());
            assertEquals(List.of(2_001, 0, -1, 2_004), other.gear().slots().subList(0, 4));
            assertEquals(new SheetModel.Live(ACCOUNT + ":8", "Wizard", 8), other.live());
        }
        assertNull(model(record(), account(), null).live());
    }

    @Test public void deathAndIdentityComeFromTheSavedRecord() {
        CharacterJournal.CharacterRecord dead = record(); dead.dead = true; dead.diedAt = NOW - 24 * HOUR;
        dead.deathAnnotation = new CharacterJournal.DeathAnnotation(); dead.deathAnnotation.markedAt = NOW - 24 * HOUR; dead.deathAnnotation.notes = "Synthetic note";
        SheetModel m = model(dead, account(), null);
        assertEquals(new SheetModel.Death(NOW - 24 * HOUR, null, "Synthetic note"), m.death());
        assertTrue(m.identity().dead()); assertEquals(Boolean.TRUE, m.identity().seasonal());
        assertEquals("Sharkbait", m.identity().name()); assertEquals("Wizard", m.identity().className());
        assertNull(model(record(), account(), null).death());
        CharacterJournal.CharacterRecord unnamed = record(); unnamed.name = null;
        assertEquals("Wizard #7", model(unnamed, account(), null).identity().name());
        assertNull("A key the journal does not have builds nothing", model(null, account(), null));
    }

    @Test public void aFieldCapturedAtAnUnknownTimeShowsUnknownNeverAnEpochDate() {
        CharacterJournal.CharacterRecord record = record();
        record.fields.put("stat.0", new FieldCapture(0, "Captured total minus boost")); // FieldCapture.at 0 means unknown timing
        SheetModel m = model(record, account(), null);
        assertEquals("Unknown timing never renders as an epoch date", "Captured total minus boost · Unknown", m.stats().evidence().get(0));
    }

    /**
     * Tier labels are part of the model, computed off the EDT from the definitions this build was given (never the global
     * RosterDefinitions.current()), so the gear section changes, and the tabs repaint, when the definitions finish loading.
     */
    @Test public void tierLabelsComeFromTheDefinitionsPassedToTheBuild() {
        SheetModel.Gear gear = model(record(), account(), null).gear();
        assertEquals("One label per slot", 28, gear.tiers().size());
        assertEquals("Weapon UT; ability empty and armor not captured have none; ring T6", List.of("UT", "", "", "T6"), gear.tiers().subList(0, 4));
        assertEquals("An explicit tier label on an inventory item", "T12", gear.tiers().get(4));
        assertEquals("Empty and not-captured inventory and backpack slots have none", Collections.nCopies(23, ""), gear.tiers().subList(5, 28));
        CharacterJournal.CharacterRecord undefined = record(); undefined.equipment[5] = 3_000;
        assertEquals("An item the definitions do not hold has no label", "", model(undefined, account(), null).gear().tiers().get(5));
        SheetModel.Gear playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null)).gear();
        assertEquals("The live equipped four are labeled too (2_003 has no definition)", List.of("UT", "", "", "T6"), playing.tiers().subList(0, 4));

        SheetModel.Gear loading = SheetModelBuilder.build(record(), account(), null, RosterDefinitions.empty(), null, NOW).gear();
        assertEquals("While the definitions load (or without them) every label is empty", Collections.nCopies(28, ""), loading.tiers());
        assertEquals("…and a null definitions reference reads as empty definitions", Collections.nCopies(28, ""),
            SheetModelBuilder.build(record(), account(), null, null, null, null, NOW).gear().tiers());
        assertEquals("The slots themselves do not depend on the definitions", loading.slots(), gear.slots());
        assertNotEquals("Labels arriving with the definitions change the gear section, so GearTab repaints it", loading, gear);
    }

    /** The sheet's pet is the record's own, named with the pet names the build was given (never the global PetDefinitions.current()). */
    @Test public void petComesFromTheRecordWithNames() throws Exception {
        CharacterJournal.CharacterRecord withPet = record(); withPet.pet = pet(NOW - HOUR);
        PetDefinitions names = petNames(temp.getRoot().toPath());
        PetSummary pet = model(withPet, account(), null, names).pet();
        assertEquals(PetSummary.State.KNOWN, pet.state());
        assertEquals("Sample pet", pet.title()); assertEquals("Rare", pet.rarity()); assertEquals("Canine", pet.family());
        assertEquals(Integer.valueOf(70), pet.maxLevel());
        assertEquals(List.of("Heal", "Magic heal", "Electric"), pet.abilities().stream().map(PetSummary.Ability::name).collect(Collectors.toList()));
        assertEquals(List.of(false, false, true), pet.abilities().stream().map(PetSummary.Ability::locked).collect(Collectors.toList()));
        assertEquals(NOW - HOUR, pet.observedAt()); assertEquals("Character list", pet.source());
        assertEquals("The same summary PetSummary builds from the record", PetSummary.of(withPet.pet, names), pet);
        assertNull("While the pet names load the family is unknown; everything else is known", model(withPet, account(), null).pet().family());
        assertEquals("Rare", model(withPet, account(), null).pet().rarity());
        assertNotEquals("Names arriving change the pet section, so the Pet tab repaints", model(withPet, account(), null), model(withPet, account(), null, names));
    }

    /** Unknown (no PetRecord) is never "No pet" (an explicitly empty pet element); every model carries a pet section. */
    @Test public void noPetAndUnknownPetStayDistinct() {
        SheetModel unknown = model(record(), account(), null);
        assertSame(PetSummary.UNKNOWN, unknown.pet());
        assertNull("Unknown shows no chip", unknown.pet().chip());
        CharacterJournal.CharacterRecord none = record(); none.pet = noPet(NOW - HOUR);
        PetSummary noPet = model(none, account(), null).pet();
        assertEquals(PetSummary.State.NONE, noPet.state()); assertEquals("No pet", noPet.chip());
        assertEquals(NOW - HOUR, noPet.observedAt());
        assertNotEquals(unknown.pet(), noPet);
        try { new SheetModel(unknown.key(), unknown.identity(), unknown.stats(), unknown.gear(), unknown.exalts(), null, null, null); fail("The pet section is never null"); }
        catch (NullPointerException expected) { }
    }

    @Test public void aMapChangesBriefClearStillCountsAsInGame() {
        LiveCharacter live = new LiveCharacter();
        LiveCharacter.Snapshot wizard = live(ACCOUNT, 7, "Sharkbait", null);
        live.publish(wizard);
        assertSame(wizard, SheetModelBuilder.inGame(live, NOW));
        live.clear(NOW, LiveCharacter.Boundary.TRANSIENT);
        assertSame("Within Home's grace a map change is not leaving the game", wizard, SheetModelBuilder.inGame(live, NOW + 1_000));
        assertNull("After it, nobody is in game", SheetModelBuilder.inGame(live, NOW + HOUR));
        live.publish(wizard); live.stop(NOW + 2_000);
        assertNull("A capture stop ends it at once", SheetModelBuilder.inGame(live, NOW + 2_001));
        assertNull(SheetModelBuilder.inGame(null, NOW));
    }
}
