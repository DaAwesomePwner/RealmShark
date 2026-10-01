package tomato.gui.loot.explore;

import java.util.List;
import org.junit.Test;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;

/** One item's drops, newest first, with where each came from, and the rarity summary line. */
public class ItemHistoryTest {
    @Test public void dropsOfOneItemNewestFirstWithTheirRunsAndRarities() {
        VisitRef run = new VisitRef("00000000-0000-4000-8000-000000000001", "v1");
        ItemHistory history = ItemHistory.of(1, List.of(
            new LootFacts.Bag("s", 100, false, "White", run, List.of(ut(1, 2), potion(4)), "Lost Halls", "Synthetic boss"),
            bag(300, ut(1, 3)),
            bag(200, ut(1, 0), ut(1, null))));
        assertEquals(4, history.count());
        assertEquals(List.of(300L, 200L, 200L, 100L), history.drops().stream().map(HighlightsModel.Notable::time).toList());
        assertEquals(run, history.drops().get(3).visit());
        assertEquals(HighlightsModel.Kind.UT, history.drops().get(0).kind());
        assertEquals(Integer.valueOf(3), history.front().slots());
        assertEquals("4 drops · 1 Rare · 1 Legendary", history.summary());
    }

    @Test public void anItemNeverLootedHasNoDrops() {
        ItemHistory history = ItemHistory.of(42, List.of(bag(100, potion(4))));
        assertEquals(0, history.count());
        assertNull(history.front());
        assertEquals("0 drops", history.summary());
    }
}
