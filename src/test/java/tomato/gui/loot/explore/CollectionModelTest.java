package tomato.gui.loot.explore;

import java.util.*;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import static org.junit.Assert.*;

/** The trophy cabinet's rules: shelves by kind, one entry per item with its count, the best variant in front, search by name. */
public class CollectionModelTest {
    static LootFacts.Item ut(int id, Integer slots) { return new LootFacts.Item(id, true, false, false, false, slots, slots == null ? null : 0); }
    static LootFacts.Item st(int id) { return new LootFacts.Item(id, false, true, false, false); }
    static LootFacts.Item tiered(int id) { return new LootFacts.Item(id, false, false, true, false, null, null, null, "T13"); }
    static LootFacts.Item potion(int id) { return new LootFacts.Item(id, false, false, false, true); }
    static LootFacts.Item plain(int id) { return new LootFacts.Item(id, false, false, false, false); }
    static LootFacts.Bag bag(long time, LootFacts.Item... items) { return new LootFacts.Bag("s", time, false, "White", null, List.of(items), "Lost Halls", "Synthetic boss"); }
    static final Map<Integer, String> NAMES = Map.of(1, "Synthetic Seal", 2, "Synthetic Robe", 3, "Synthetic Staff", 4, "Potion of Life", 5, "Synthetic Rock");

    @Test public void itemsLandOnTheirShelfWithCountsAndTheBestVariantInFront() {
        CollectionModel model = CollectionModel.of(List.of(
            bag(100, ut(1, 0), potion(4), potion(4)),
            bag(200, ut(1, 3), st(2), tiered(3), plain(5)),
            bag(300, ut(1, 1), potion(4))), "", NAMES::get);
        assertEquals(List.of(CollectionModel.Kind.UT, CollectionModel.Kind.ST, CollectionModel.Kind.TIERED, CollectionModel.Kind.POTIONS, CollectionModel.Kind.OTHER),
            model.shelves().stream().map(CollectionModel.Shelf::kind).toList());
        CollectionModel.Entry seal = model.shelves().get(0).entries().get(0);
        assertEquals(1, seal.itemId());
        assertEquals(3, seal.count());
        assertEquals("The Legendary (3 slots) variant is in front", Integer.valueOf(3), seal.front().slots());
        assertEquals(300, seal.last());
        assertEquals(3, model.shelves().get(3).entries().get(0).count());
        assertEquals(9, model.drops());
        assertEquals(5, model.kinds());
    }

    @Test public void entriesSortByCountThenNewest() {
        CollectionModel model = CollectionModel.of(List.of(bag(100, potion(4)), bag(200, potion(6)), bag(300, potion(6))), "",
            id -> "Potion " + id);
        assertEquals(List.of(6, 4), model.shelves().get(0).entries().stream().map(CollectionModel.Entry::itemId).toList());
    }

    @Test public void searchKeepsItemsWhoseNameMatchesAndEmptyShelvesLeave() {
        CollectionModel model = CollectionModel.of(List.of(bag(100, ut(1, null), st(2), potion(4))), "  ROBE ", NAMES::get);
        assertEquals(1, model.shelves().size());
        assertEquals(CollectionModel.Kind.ST, model.shelves().get(0).kind());
        assertTrue(CollectionModel.of(List.of(), "", NAMES::get).shelves().isEmpty());
    }
}
