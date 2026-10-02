package tomato.gui.loot.explore;

import java.util.List;
import org.junit.Test;
import tomato.gui.runs.DungeonCardModel;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;

/** The dungeon wall: runs from Runs › Dungeons joined with saved loot, rates per run with loot, sorts, search, no unknown areas. */
public class AtlasModelTest {
    static DungeonCardModel card(String canonical, int visits, long last) {
        return new DungeonCardModel(canonical, canonical, 0, visits, 0, 0, 0, 0, 0, null, null, 0, null, 0, 0, null, null, null, null,
            null, null, null, null, last);
    }
    static LootFacts.Bag bag(String dungeon, String visit, long time, boolean white, LootFacts.Item... items) {
        return new LootFacts.Bag("s", time, white, white ? "White" : "Brown",
            visit == null ? null : new VisitRef("00000000-0000-4000-8000-00000000000" + visit.length(), visit), List.of(items), dungeon, "Synthetic boss");
    }

    @Test public void cardsAndLootJoinByCanonicalDungeon() {
        AtlasModel atlas = AtlasModel.of(List.of(card("Lost Halls", 10, 500), card("Snake Pit", 3, 100)),
            List.of(bag("Lost Halls", "a", 400, true, ut(1, null)), bag("Lost Halls", "b", 450, false, ut(1, null), potion(4)),
                bag("Sprite World", "c", 600, true, st(2)), bag(LootFacts.UNRECOGNIZED, "d", 700, true, ut(9, null)), bag(null, null, 800, true)),
            AtlasModel.Sort.RUNS, "");
        assertEquals(List.of("Lost Halls", "Snake Pit", "Sprite World"), atlas.tiles().stream().map(AtlasModel.Tile::dungeon).toList());
        AtlasModel.Tile halls = atlas.tiles().get(0);
        assertEquals(Integer.valueOf(10), halls.runs());
        assertEquals(2, halls.lootRuns());
        assertEquals(1, halls.whites());
        assertEquals(2, halls.uts());
        assertEquals(0.5, halls.whitesPerRun(), 1e-9);
        assertEquals(1.0, halls.utsPerRun(), 1e-9);
        assertEquals(List.of(1), halls.top().stream().map(DungeonStats.Item::id).toList());
        assertEquals(500, halls.last());
        AtlasModel.Tile sprite = atlas.tiles().get(2);
        assertNull("Only loot knows Sprite World", sprite.runs());
        assertNull("Snake Pit has no loot", atlas.tiles().get(1).whitesPerRun());
    }

    @Test public void sortsAndSearch() {
        List<DungeonCardModel> cards = List.of(card("Lost Halls", 10, 500), card("Snake Pit", 3, 900));
        List<LootFacts.Bag> bags = List.of(bag("Lost Halls", "a", 400, false, ut(1, null)), bag("Snake Pit", "b", 300, true));
        assertEquals("Snake Pit", AtlasModel.of(cards, bags, AtlasModel.Sort.WHITES, "").tiles().get(0).dungeon());
        assertEquals("Lost Halls", AtlasModel.of(cards, bags, AtlasModel.Sort.UTS, "").tiles().get(0).dungeon());
        assertEquals("Snake Pit", AtlasModel.of(cards, bags, AtlasModel.Sort.RECENT, "").tiles().get(0).dungeon());
        assertEquals(List.of("Snake Pit"), AtlasModel.of(cards, bags, AtlasModel.Sort.RUNS, " snake ").tiles().stream().map(AtlasModel.Tile::dungeon).toList());
    }

    @Test public void unknownAreasAreExcludedAndTopDropsAreLimitedToThree() {
        List<DungeonCardModel> cards = List.of(card(LootFacts.UNKNOWN_AREA, 99, 100), card(LootFacts.UNKNOWN, 88, 100),
            card(LootFacts.UNRECOGNIZED, 77, 100), card("", 66, 100));
        List<LootFacts.Bag> bags = List.of(bag("Lost Halls", "a", 100, true, ut(1, null), ut(2, null), ut(3, null), ut(4, null)),
            bag("Lost Halls", "a", 200, true, ut(1, null)), bag(LootFacts.UNKNOWN_AREA, null, 300, true));
        AtlasModel atlas = AtlasModel.of(cards, bags, AtlasModel.Sort.RUNS, null);
        assertEquals(1, atlas.tiles().size());
        AtlasModel.Tile tile = atlas.tiles().get(0);
        assertNull(tile.runs());
        assertEquals("Bags in the same run share a denominator", 1, tile.lootRuns());
        assertEquals(List.of(1, 2, 3), tile.top().stream().map(DungeonStats.Item::id).toList());
        assertEquals(200, tile.last());
    }
}
