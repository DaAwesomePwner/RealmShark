package tomato.gui.loot.haul;

import java.util.*;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import tomato.gui.stats.LootQuery;
import static org.junit.Assert.*;

/** The haul's pure rules: best drop order, bag shelf order, the default open bag, the tally and exact variant keys. */
public class HaulModelTest {
    private static LootFacts.Item item(int id, boolean ut, boolean st, boolean potion, Integer slots, String tier) {
        return new LootFacts.Item(id, ut, st, false, potion, slots, slots == null ? null : 0, null, tier);
    }
    private static HaulModel.Bag bag(String name, long time, LootFacts.Item... items) { return new HaulModel.Bag(name, time, null, List.of(items)); }

    @Test public void theBestDropIsUtThenStThenRarityThenTierThenPotion() {
        List<LootFacts.Item> ladder = List.of(
            item(1, false, false, false, null, null),   // nothing known
            item(2, false, false, true, null, null),    // potion
            item(3, false, false, false, null, "T12"),  // tiered
            item(4, false, false, false, null, "T13"),  // higher tier
            item(5, false, false, false, 0, "T4"),      // known unenchanted beats unknown rarity
            item(6, false, false, false, 2, "T10"),     // Rare
            item(7, false, false, false, 4, "T5"),      // Divine
            item(8, false, true, false, null, null),    // ST
            item(9, true, false, false, null, null));   // UT
        for (int n = 1; n <= ladder.size(); n++) {
            List<LootFacts.Item> reversed = new ArrayList<>(ladder.subList(0, n));
            Collections.reverse(reversed);
            HaulModel haul = HaulModel.of(null, List.of(new HaulModel.Bag("White", 1, null, reversed)));
            assertEquals("Best of the first " + n, n, haul.hero().item().id());
        }
    }

    @Test public void tiesKeepTheEarliestDrop() {
        HaulModel haul = HaulModel.of(null, List.of(bag("White", 200, item(1, true, false, false, null, null)), bag("Red", 100, item(2, true, false, false, null, null))));
        assertEquals(2, haul.hero().item().id());
        assertEquals("Red", haul.hero().bag());
        assertEquals(100, haul.hero().time());
    }

    @Test public void oddTierLabelsCountAsNoTier() {
        HaulModel haul = HaulModel.of(null, List.of(bag("Brown", 1, item(1, false, false, false, null, "T"), item(2, false, false, false, null, "Tx"), item(3, false, false, false, null, "T2"))));
        assertEquals(3, haul.hero().item().id());
    }

    @Test public void theShelfRanksBagsByValueWithBoostedAboveTheirBaseAndUnknownLast() {
        HaulModel haul = HaulModel.of(null, List.of(bag("Purple", 1), bag("White", 2), bag(null, 3), bag("B.White", 4), bag("Purple", 5), bag("Mystery", 6)));
        assertEquals(Arrays.asList("B.White", "White", "Purple", null, "Mystery"), haul.shelf().stream().map(HaulModel.Shelf::bag).toList());
        assertEquals("Newest bag first within a group", List.of(5L, 1L), haul.shelf().get(2).bags().stream().map(HaulModel.Bag::time).toList());
        assertEquals("No items: no best drop, the first group opens", 0, haul.openByDefault());
        assertNull(haul.hero());
    }

    @Test public void theBestDropsBagOpensByDefault() {
        HaulModel haul = HaulModel.of(null, List.of(bag("White", 1, item(1, false, false, true, null, null)), bag("Pink", 2, item(2, true, false, false, null, null))));
        assertEquals(List.of("White", "Pink"), haul.shelf().stream().map(HaulModel.Shelf::bag).toList());
        assertEquals(1, haul.openByDefault());
    }

    @Test public void theTallyCountsItemsBagsAndNotableKinds() {
        HaulModel haul = HaulModel.of(null, List.of(bag("White", 1, item(1, true, false, false, null, null)),
            bag("Purple", 2, item(2, false, false, true, null, null), item(3, false, false, true, null, null))));
        assertEquals("3 items in 2 bags · 1 UT · 2 potions", haul.tally());
        assertEquals(3, haul.items());
        assertEquals(1, haul.shelf().get(0).items());
        assertEquals(2, haul.shelf().get(1).items());
    }

    @Test public void anEmptyHaulHasNoBestDropNoShelfAndNoTally() {
        HaulModel haul = HaulModel.of(null, List.of());
        assertNull(haul.hero());
        assertTrue(haul.shelf().isEmpty());
        assertEquals(-1, haul.openByDefault());
        assertEquals("", haul.tally());
        assertEquals(0, haul.items());
    }

    @Test public void variantKeysAreLootQuerysExactVariant() {
        LootFacts.Item enchanted = new LootFacts.Item(7, false, false, false, false, 2, 1), legacy = new LootFacts.Item(8, false, false, false, false);
        assertEquals("7/2/1", HaulModel.variantKey(enchanted));
        assertEquals("8/null/null", HaulModel.variantKey(legacy));
        for (LootFacts.Item item : List.of(enchanted, legacy)) {
            LootQuery.Facets facets = new LootQuery.Facets();
            facets.variant = HaulModel.variantKey(item);
            facets.validate();   // throws when the key is not an exact variant
        }
    }
}
