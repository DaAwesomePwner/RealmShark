package tomato.gui.glance.character;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.ExaltFixtures.*;

/** The account Exalts grid's model: one tile per observed class, per-class loot boost over saved counts, the header's class and the account choice. */
public class AccountExaltsBuilderTest {
    private static AccountExalts build(List<AccountRecord> accounts, List<CharacterRecord> records, String preferred,
                                       tomato.backend.data.LiveCharacter.Snapshot live) {
        return AccountExaltsBuilder.build(accounts, records, preferred, live, ExaltFixtures::weaponGroup, ExaltFixtures::className);
    }

    @Test public void tilesFollowClassOrderWithTheSheetsTiersTotalsAndLowest() {
        AccountExalts model = build(List.of(first()), List.of(), FIRST, null);
        assertEquals(FIRST, model.account());
        assertEquals("One tile per observed class, ordered by class id", List.of(WIZARD, PRIEST, WARRIOR),
            model.tiles().stream().map(AccountExalts.Tile::classId).toList());
        AccountExalts.Tile wizard = model.tiles().get(0);
        SheetModel.Exalts sheet = SheetModelBuilder.exalts(WIZARD, first(), null);
        assertEquals("Wizard", wizard.className());
        assertEquals("Canonical order, as the sheet's Exalts tab", List.of(3, 4, 3, 5, 3, 3, 4, 3), wizard.tiers());
        assertEquals(sheet.tiers(), wizard.tiers());
        assertEquals(365, wizard.total());
        assertEquals(sheet.total(), wizard.total());
        assertEquals(3, wizard.lowest());
        assertEquals("When this class's counts last changed", NOW - HOUR, wizard.seenAt());
        AccountExalts.Tile priest = model.tiles().get(1), warrior = model.tiles().get(2);
        assertEquals(5, priest.lowest());
        assertEquals(0, warrior.lowest());
        assertEquals(List.of(1, 0, 0, 0, 0, 0, 0, 0), warrior.tiers());
        assertEquals(5, warrior.total());
    }

    @Test public void lootBoostComesFromTheSavedCountsAndTheWeaponGroupsPassedIn() {
        AccountRecord account = first();
        AccountExalts model = build(List.of(account), List.of(), FIRST, null);
        assertEquals("Wizard and Priest share a weapon; the Wizard's lowest tier 3 gives 15", Integer.valueOf(15), model.tiles().get(0).lootBoost());
        assertEquals("The Priest's boost is its whole group's, not its own maxed tier", Integer.valueOf(15), model.tiles().get(1).lootBoost());
        assertEquals("A Warrior count below 5 (and no Knight counts) is a known 0", Integer.valueOf(0), model.tiles().get(2).lootBoost());
        assertEquals(RealmCharacter.exaltLootBonus(account.exalts, weaponGroup(WIZARD)), model.tiles().get(0).lootBoost().intValue());
        // Another weapon grouping gives another boost: the builder never reads CharacterClass's asset groups itself.
        AccountExalts alone = AccountExaltsBuilder.build(List.of(first()), List.of(), FIRST, null,
            id -> new int[] {id}, ExaltFixtures::className);
        assertEquals("The Priest alone is maxed", Integer.valueOf(25), alone.tiles().get(1).lootBoost());
    }

    @Test public void aClassWithoutAWeaponGroupHasAnUnknownBoostNeverZero() {
        AccountRecord archer = account(FIRST, "Sample", ARCHER, WIZARD_COUNTS, WIZARD, WIZARD_COUNTS);
        AccountExalts model = build(List.of(archer), List.of(), FIRST, null);
        assertEquals(ARCHER, model.tiles().get(0).classId());
        assertNull("The selected assets do not name the Archer's weapon group: unknown", model.tiles().get(0).lootBoost());
        AccountExalts noAssets = AccountExaltsBuilder.build(List.of(first()), List.of(), FIRST, null, id -> null, ExaltFixtures::className);
        for (AccountExalts.Tile tile : noAssets.tiles()) assertNull("No class data at all: every boost unknown", tile.lootBoost());
        assertNull(noAssets.headerBoost());
    }

    @Test public void theHeaderShowsTheClassInGameElseTheClassLastPlayed() {
        List<CharacterRecord> records = List.of(record(FIRST, 7, WIZARD, NOW - 2 * HOUR, false), record(FIRST, 8, WARRIOR, NOW - 5 * HOUR, false),
            record(FIRST, 9, PRIEST, NOW - HOUR, true), record(SECOND, 3, KNIGHT, NOW, false));
        AccountExalts inGame = build(List.of(first()), records, FIRST, live(FIRST, 8, WARRIOR));
        assertEquals("Warrior", inGame.headerClass());
        assertEquals("in game", inGame.headerBasis());
        assertEquals(Integer.valueOf(0), inGame.headerBoost());
        AccountExalts lastPlayed = build(List.of(first()), records, FIRST, null);
        assertEquals("The newest alive character of the shown account; a dead one and another account's are skipped", "Wizard", lastPlayed.headerClass());
        assertEquals("last played", lastPlayed.headerBasis());
        assertEquals(Integer.valueOf(15), lastPlayed.headerBoost());
        AccountExalts otherAccountInGame = build(List.of(first()), records, FIRST, live(SECOND, 3, KNIGHT));
        assertEquals("Another account's character in game is not this account's class", "Wizard", otherAccountInGame.headerClass());
        assertEquals("last played", otherAccountInGame.headerBasis());
        AccountExalts noClass = build(List.of(first()), List.of(record(FIRST, 7, WIZARD, 0, false)), FIRST, null);
        assertNull("A character never seen in game is not \"last played\"", noClass.headerClass());
        assertNull(noClass.headerBasis());
        assertNull(noClass.headerBoost());
        AccountExalts unknownGroup = build(List.of(first()), List.of(), FIRST, live(FIRST, 4, ARCHER));
        assertEquals("Archer", unknownGroup.headerClass());
        assertNull("The class is known but its weapon group is not", unknownGroup.headerBoost());
    }

    @Test public void thePreferredAccountShowsWhenItHasCountsElseTheFirstOffered() {
        AccountRecord empty = account(tomato.backend.data.CharacterJournal.accountKey("exalt-fixture-none"), "Nobody");
        List<AccountRecord> accounts = List.of(empty, first(), second());
        AccountExalts preferred = build(accounts, List.of(), SECOND, null);
        assertEquals(SECOND, preferred.account());
        assertEquals(List.of(KNIGHT), preferred.tiles().stream().map(AccountExalts.Tile::classId).toList());
        assertEquals("Only accounts with saved counts are offered, in journal order; an unnamed one shows its key's start",
            List.of(new AccountExalts.Choice(FIRST, "Sample"), new AccountExalts.Choice(SECOND, "Account " + SECOND.substring(0, 6))), preferred.accounts());
        assertEquals("An account without counts falls back to the first offered", FIRST, build(accounts, List.of(), empty.key, null).account());
        assertEquals(FIRST, build(accounts, List.of(), null, null).account());
        AccountExalts one = build(List.of(first()), List.of(), SECOND, null);
        assertEquals("One account: a single choice (the selector hides)", List.of(new AccountExalts.Choice(FIRST, "Sample")), one.accounts());
    }

    @Test public void twoAccountsWithTheSameNameAreToldApartByTheirKeys() {
        AccountRecord twin = second(); twin.name = "Sample";
        List<AccountExalts.Choice> choices = build(List.of(first(), twin), List.of(), FIRST, null).accounts();
        assertEquals("Sample · " + FIRST.substring(0, 6), choices.get(0).label());
        assertEquals("Sample · " + SECOND.substring(0, 6), choices.get(1).label());
    }

    @Test public void noSavedCountsIsAnEmptyModel() {
        AccountExalts none = build(List.of(account(FIRST, "Sample")), List.of(record(FIRST, 7, WIZARD, NOW, false)), FIRST, live(FIRST, 7, WIZARD));
        assertNull(none.account());
        assertTrue(none.accounts().isEmpty());
        assertTrue(none.tiles().isEmpty());
        assertNull(none.headerBoost());
        assertNull(none.headerClass());
        assertEquals(0, none.fullyExalted());
        assertEquals(0, none.observed());
        assertEquals(none, build(List.of(), List.of(), null, null));
    }

    @Test public void fullyExaltedCountsClassesWhoseLowestTierIsFive() {
        AccountExalts model = build(List.of(first()), List.of(), FIRST, null);
        assertEquals("Only the Priest", 1, model.fullyExalted());
        assertEquals(3, model.observed());
        AccountExalts two = build(List.of(account(FIRST, "Sample", WIZARD, PRIEST_COUNTS, PRIEST, PRIEST_COUNTS)), List.of(), FIRST, null);
        assertEquals(2, two.fullyExalted());
        assertEquals(2, two.observed());
    }

    @Test public void theClassDetailsAreTheSheetsExaltsForTheShownAccount() {
        List<String> lostHalls = List.of("Lost Halls");
        Map<Integer, SheetModel.Exalts> details = AccountExaltsBuilder.details(List.of(first(), second()), FIRST, stat -> stat == 3 ? lostHalls : List.of());
        assertEquals(List.of(WIZARD, PRIEST, WARRIOR), List.copyOf(details.keySet()));
        assertEquals(SheetModelBuilder.exalts(WIZARD, first(), stat -> stat == 3 ? lostHalls : List.of()), details.get(WIZARD));
        assertEquals("Lost Halls", details.get(WIZARD).earnIn().get(3));
        assertNull("The mapping is loading", AccountExaltsBuilder.details(List.of(first()), FIRST, null).get(WIZARD).earnIn());
        assertTrue(AccountExaltsBuilder.details(List.of(first()), null, null).isEmpty());
    }

    @Test public void theCurrentAccountIsLiveThenLastKnownThenTheMostRecentCharacters() {
        CharacterRecord recent = record(SECOND, 3, KNIGHT, NOW, false);
        assertEquals(FIRST, AccountExaltsBuilder.currentAccount(live(FIRST, 7, WIZARD), live(SECOND, 3, KNIGHT), recent));
        assertEquals(SECOND, AccountExaltsBuilder.currentAccount(null, live(SECOND, 3, KNIGHT), record(FIRST, 7, WIZARD, NOW, false)));
        assertEquals(SECOND, AccountExaltsBuilder.currentAccount(null, null, recent));
        assertNull(AccountExaltsBuilder.currentAccount(null, null, null));
    }
}
