package tomato.gui.stats;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.stats.LootTestDrops.Kind.*;
import static tomato.gui.stats.LootTestDrops.item;

/** The public loot projection keeps saved classifications and only the exact recorded visit. Synthetic data. */
public class LootFactsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void projectsSavedBagsWithExactVisitsAndItemFlags() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String id = store.currentId(); VisitRef run = new VisitRef(id, "v1");
            store.append("loot", LootTestDrops.drop("White", "Lost Halls", 1_000, run, item(1, UT), item(2, POTION)));
            store.append("loot", LootTestDrops.drop("B.White", "Lost Halls", 2_000, null, item(3, ST)));
            store.append("loot", LootTestDrops.drop("Orange", "Ice Citadel", 3_000, new VisitRef(id, "v2"), item(4, TIERED), item(5, PLAIN)));
            store.flush();
            List<LootFacts.Bag> bags = new ArrayList<>();
            LootFacts.read(store, id, bags::add);
            assertEquals(3, bags.size());
            List<LootFacts.Bag> again = new ArrayList<>();
            LootFacts.read(store, store.catalog(), id, again::add);
            assertEquals("One catalog listing serves several reads", bags, again);
            LootFacts.Bag white = bags.get(0);
            assertEquals(id, white.session()); assertEquals(1_000, white.time()); assertTrue(white.white()); assertEquals(run, white.visit());
            assertEquals(List.of(new LootFacts.Item(1, true, false, false, false), new LootFacts.Item(2, false, false, false, true)), white.items());
            assertTrue("Boosted white bags are white bags", bags.get(1).white());
            assertNull("No recorded visit: none is inferred", bags.get(1).visit());
            assertEquals(new LootFacts.Item(3, false, true, false, false), bags.get(1).items().get(0));
            assertFalse(bags.get(2).white());
            assertEquals(List.of(new LootFacts.Item(4, false, false, true, false), new LootFacts.Item(5, false, false, false, false)), bags.get(2).items());
        }
    }

    @Test public void aBagWithoutItsItemListFailsTheReadInsteadOfCountingAsEmpty() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            store.append("loot", SessionStore.JSON.fromJson("{\"bag\":\"White\",\"dungeon\":\"Lost Halls\",\"dropper\":\"Boss\",\"time\":5}", LootDashboard.Drop.class));
            store.flush();
            IOException failure = assertThrows(IOException.class, () -> LootFacts.read(store, store.currentId(), bag -> { }));
            assertTrue(failure.getMessage(), failure.getMessage().contains("item list"));
        }
    }
}
