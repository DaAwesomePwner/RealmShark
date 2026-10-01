package tomato.gui.loot.explore;

import java.util.*;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import tomato.realmshark.enums.StatPotion;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;

public class DungeonStatsTest {
    static final String DUNGEON = "Synthetic Halls";
    private static final int LIFE = StatPotion.Life.smallId();
    static LootFacts.Bag drop(long time, String session, String visit, boolean white, LootFacts.Item... items) {
        return new LootFacts.Bag(session, time, white, white ? "B.White" : "Purple",
            visit == null ? null : new VisitRef(session, visit), List.of(items), DUNGEON, null);
    }

    @Test public void countsDropTimeRunsAndAllVariantsIncludingUnlinkedBags() {
        List<LootFacts.Bag> bags = List.of(
            drop(100, "s1", "v1", true, ut(1, 0), st(2), potion(LIFE)),
            drop(200, "s1", "v1", true, ut(1, 3), potion(LIFE)),
            drop(300, "s2", "v1", false, ut(1, null), tiered(3)),
            drop(400, "s2", null, false, st(2), plain(8)),
            bag(500, ut(1, 4))); // another canonical dungeon does not count
        DungeonStats stats = DungeonStats.of(bags, DUNGEON);
        assertEquals(4, stats.bags());
        assertEquals(2, stats.runs());
        assertEquals(2, stats.whites());
        assertEquals(3, stats.uts());
        assertEquals(2, stats.sts());
        assertEquals(2, stats.potions());
        assertEquals(List.of(new DungeonStats.Item(1, 3, 300), new DungeonStats.Item(2, 2, 400), new DungeonStats.Item(3, 1, 300)), stats.mostDropped());
    }

    @Test public void topSixSortByCountThenNewestThenId() {
        List<LootFacts.Bag> bags = new ArrayList<>();
        bags.add(drop(1, "s", "v", false, ut(9, null), ut(9, 4)));
        bags.add(drop(300, "s", "v", false, tiered(7), st(6)));
        bags.add(drop(200, "s", "v", false, ut(1, 4), ut(2, 0), ut(3, null), ut(4, 2), ut(5, 1)));
        assertEquals(List.of(9, 6, 7, 1, 2, 3), DungeonStats.of(bags, DUNGEON).mostDropped().stream().map(DungeonStats.Item::id).toList());
    }

    @Test public void onlyRecognizedStatPotionsWithTheSavedPotionClassificationCount() {
        assertNull("Synthetic non-stat potion", LootFacts.potionStat(4));
        assertEquals("Life", LootFacts.potionStat(LIFE));
        DungeonStats stats = DungeonStats.of(List.of(drop(100, "s", "v", false, potion(4), potion(LIFE), plain(LIFE))), DUNGEON);
        assertEquals(1, stats.potions());
    }

    @Test public void unknownDungeonNeverGroupsUnknownBagsOrGuesses() {
        List<LootFacts.Bag> bags = List.of(new LootFacts.Bag("s", 1, true, "White", null, List.of(ut(1, 4))));
        for (String unknown : Arrays.asList(null, "", " ", LootFacts.UNKNOWN, LootFacts.UNRECOGNIZED, LootFacts.UNKNOWN_AREA)) {
            DungeonStats stats = DungeonStats.of(bags, unknown);
            assertNull(stats.dungeon());
            assertEquals(0, stats.bags());
            assertEquals(0, stats.runs());
            assertTrue(stats.mostDropped().isEmpty());
        }
        assertEquals(DUNGEON, DungeonStats.of(List.of(), DUNGEON).dungeon());
    }
}
