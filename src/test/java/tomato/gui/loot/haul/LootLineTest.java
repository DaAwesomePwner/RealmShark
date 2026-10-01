package tomato.gui.loot.haul;

import java.util.List;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import static org.junit.Assert.*;

/** The one loot wording shared by run cards, the run recap and the haul. */
public class LootLineTest {
    private static LootFacts.Item ut(int id) { return new LootFacts.Item(id, true, false, false, false); }
    private static LootFacts.Item potion(int id) { return new LootFacts.Item(id, false, false, false, true); }

    @Test public void kindsCountUntieredSetTieredAndPotionsOnly() {
        assertEquals("1 UT · 2 potions", LootLine.kinds(List.of(ut(1), potion(2), potion(3))));
        assertEquals("1 ST · 1 potion", LootLine.kinds(List.of(new LootFacts.Item(4, false, true, false, false), potion(5))));
        assertEquals("", LootLine.kinds(List.of(new LootFacts.Item(6, false, false, true, false))));
    }

    @Test public void sectionPutsTheCountsFirst() {
        assertEquals("3 items in 2 bags · 1 UT · 2 potions", LootLine.section(3, 2, "1 UT · 2 potions"));
        assertEquals("1 item in 1 bag", LootLine.section(1, 1, ""));
    }
}
