package tomato.gui.stats;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;
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
            // P6a: items carry their enchant slots and applied count; these recorded empty enchant data (0, 0), the potion none (unknown).
            assertEquals(List.of(new LootFacts.Item(1, true, false, false, false, 0, 0), new LootFacts.Item(2, false, false, false, true, null, null)), white.items());
            assertTrue("Boosted white bags are white bags", bags.get(1).white());
            assertNull("No recorded visit: none is inferred", bags.get(1).visit());
            assertEquals(new LootFacts.Item(3, false, true, false, false, 0, 0), bags.get(1).items().get(0));
            assertFalse(bags.get(2).white());
            assertEquals(List.of(new LootFacts.Item(4, false, false, true, false, 0, 0), new LootFacts.Item(5, false, false, false, false, 0, 0)), bags.get(2).items());
        }
    }

    @Test public void itemsCarryExactEnchantsWhenCapturedAndTheRarityForOlderRecords() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String id = store.currentId();
            String rare = enchants(-1, 42);
            store.append("loot", new LootDashboard.Drop("White", "Lost Halls", "Boss", 1_000, List.of(
                new LootDashboard.Item(10, "Item #10", "EQUIPMENT,WEAPON,T12", ParseEnchants.evidence(rare)))));
            String item = "{\"id\":%d,\"name\":\"Legacy\",\"tier\":\"T4\",\"potion\":false,\"ut\":false,\"st\":false,\"highTier\":false%s}";
            store.append("loot", SessionStore.JSON.fromJson("{\"bag\":\"Brown\",\"dungeon\":\"Lost Halls\",\"dropper\":\"Boss\",\"time\":2000,\"items\":["
                + String.format(item, 11, ",\"enchants\":{\"slots\":3,\"applied\":-1}") + ","
                + String.format(item, 12, "") + ","
                + String.format(item, 13, ",\"enchants\":{\"slots\":2,\"applied\":1},\"enchantEvidence\":{\"slots\":2,\"applied\":1}") + "]}",
                LootDashboard.Drop.class));
            store.flush();
            List<LootFacts.Bag> bags = new ArrayList<>();
            LootFacts.read(store, id, bags::add);
            assertEquals("Captured evidence: the exact slots", EnchantInfo.of(rare), bags.get(0).items().get(0).enchant());
            EnchantInfo legacy = bags.get(1).items().get(0).enchant();
            assertEquals("A saved slot count alone: the rarity", EnchantInfo.State.COUNT_ONLY, legacy.state());
            assertEquals(EnchantInfo.Rarity.LEGENDARY, legacy.rarity());
            assertSame("Nothing saved: not recorded", EnchantInfo.notRecorded(), bags.get(1).items().get(1).enchant());
            EnchantInfo partial = bags.get(1).items().get(2).enchant();
            assertEquals("Evidence without a state falls back to the saved count, never throws", EnchantInfo.Rarity.RARE, partial.rarity());
        }
    }

    @Test public void bagNamesAreKeptAsRecordedAndUnknownForLegacyDrops() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String id = store.currentId(); VisitRef run = new VisitRef(id, "v1");
            store.append("loot", LootTestDrops.drop("White", "Lost Halls", 1_000, run, item(1, UT)));
            store.append("loot", LootTestDrops.drop("B.White", "Lost Halls", 2_000, run, item(2, ST)));
            store.append("loot", LootTestDrops.drop("Orange", "Lost Halls", 3_000, run, item(3, TIERED)));
            // Legacy drops: no bag name saved, or an empty one.
            store.append("loot", SessionStore.JSON.fromJson("{\"dungeon\":\"Lost Halls\",\"dropper\":\"Boss\",\"time\":4000,\"items\":[]}", LootDashboard.Drop.class));
            store.append("loot", SessionStore.JSON.fromJson("{\"bag\":\"\",\"dungeon\":\"Lost Halls\",\"dropper\":\"Boss\",\"time\":5000,\"items\":[]}", LootDashboard.Drop.class));
            store.flush();
            List<LootFacts.Bag> bags = new ArrayList<>();
            LootFacts.read(store, id, bags::add);
            assertEquals(5, bags.size());
            assertEquals("White", bags.get(0).bag()); assertEquals("B.White", bags.get(1).bag()); assertEquals("Orange", bags.get(2).bag());
            assertTrue("white() is unchanged beside the name", bags.get(0).white() && bags.get(1).white() && !bags.get(2).white());
            assertNull("Not recorded: unknown, never a guessed bag", bags.get(3).bag()); assertFalse(bags.get(3).white());
            assertNull("An empty name is not a bag name", bags.get(4).bag());
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

    // P6a: the bag's dungeon and dropper and each item's enchant slots, projected from fields the saved drop already has.

    @Test public void savedBagsCarryTheirCanonicalDungeonAndDropperAndUnknownStaysUnknown() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String id = store.currentId();
            store.append("loot", new LootDashboard.Drop("White", "mgm2 Dungeon", "Oryx the Mad God 2", 1_000, List.of()));
            store.append("loot", new LootDashboard.Drop("White", "Lost Halls", "Marble Colossus", 2_000, List.of()));
            store.append("loot", new LootDashboard.Drop("Brown", "Unknown", "Unknown", 3_000, List.of()));   // capture's words for none
            store.append("loot", new LootDashboard.Drop("Brown", "", " ", 4_000, List.of()));
            store.append("loot", SessionStore.JSON.fromJson("{\"bag\":\"Brown\",\"time\":5000,\"items\":[]}", LootDashboard.Drop.class));
            store.flush();
            List<LootFacts.Bag> bags = new ArrayList<>();
            LootFacts.read(store, id, bags::add);
            assertEquals(5, bags.size());
            assertEquals("A saved alias reads as its canonical dungeon", "The Trials of Cronus", bags.get(0).dungeon());
            assertEquals("Oryx the Mad God 2", bags.get(0).dropper());
            assertEquals("Lost Halls", bags.get(1).dungeon()); assertEquals("Marble Colossus", bags.get(1).dropper());
            for (int i = 2; i < 5; i++) {
                assertNull("Unknown, empty or not saved: no dungeon is guessed (" + i + ")", bags.get(i).dungeon());
                assertNull("…and no dropper (" + i + ")", bags.get(i).dropper());
            }
        }
    }

    @Test public void savedItemsCarryEnchantSlotsAndAppliedAndLegacyDataIsUnknown() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String id = store.currentId();
            store.append("loot", new LootDashboard.Drop("White", "Lost Halls", "Boss", 1_000, List.of(
                new LootDashboard.Item(10, "Item #10", "EQUIPMENT,WEAPON,T12", ParseEnchants.evidence(enchants(-1, 42))),   // Rare: 2 slots, 1 applied
                new LootDashboard.Item(11, "Item #11", "EQUIPMENT,RING,T4", ParseEnchants.evidence(enchants(-1))),          // Uncommon: 1 slot, empty
                new LootDashboard.Item(12, "Item #12", "EQUIPMENT,RING,T4", ParseEnchants.evidence("")),                    // recorded: no slots
                new LootDashboard.Item(13, "Item #13", "EQUIPMENT,RING,T4", ParseEnchants.evidence(null)))));              // not captured
            // Legacy items: no enchant data saved, the -1 "unknown" of older builds, and known slots with an unknown applied count.
            String item = "{\"id\":%d,\"name\":\"Legacy\",\"tier\":\"T4\",\"potion\":false,\"ut\":false,\"st\":false,\"highTier\":false%s}";
            store.append("loot", SessionStore.JSON.fromJson("{\"bag\":\"Brown\",\"dungeon\":\"Lost Halls\",\"dropper\":\"Boss\",\"time\":2000,\"items\":["
                + String.format(item, 14, "") + "," + String.format(item, 15, ",\"enchants\":{\"slots\":-1,\"applied\":-1}") + ","
                + String.format(item, 16, ",\"enchants\":{\"slots\":3,\"applied\":-1}") + "]}", LootDashboard.Drop.class));
            store.flush();
            List<LootFacts.Bag> bags = new ArrayList<>();
            LootFacts.read(store, id, bags::add);
            List<LootFacts.Item> items = new ArrayList<>(bags.get(0).items()); items.addAll(bags.get(1).items());
            assertEquals(7, items.size());
            assertEnchants(items.get(0), 2, 1, true);
            assertEnchants(items.get(1), 1, 0, false);
            assertEnchants(items.get(2), 0, 0, false);
            for (int i : new int[] {3, 4, 5}) {
                assertNull("Not captured, not saved or legacy -1: unknown (" + items.get(i).id() + ")", items.get(i).slots());
                assertNull(items.get(i).applied());
                assertFalse("Unknown enchant data is never counted as enchanted", items.get(i).enchanted());
                assertFalse("…nor as known", items.get(i).enchantKnown());
            }
            assertEnchants(items.get(6), 3, null, true);
        }
    }

    @Test public void statPotionsNeverCarryEnchantments() {
        LootDashboard.Item live = new LootDashboard.Item(30, "Potion of Life", "STATPOTION", ParseEnchants.evidence(""));
        assertSame("A live potion's empty blob is not an enchant record", EnchantInfo.notRecorded(), live.enchantInfo());
        LootDashboard.Item summarized = new LootDashboard.Item(31, "Potion of Mana", "STATPOTION", ParseEnchants.summarize(""));
        assertSame(EnchantInfo.notRecorded(), summarized.enchantInfo());
        LootFacts.Bag bag = LootFacts.bag("live", new LootDashboard.Drop("White", "Lost Halls", "Boss", 1_000, List.of(live)));
        assertSame("…so highlights, run cards and recaps show no enchant line for it", EnchantInfo.notRecorded(), bag.items().get(0).enchant());
    }

    @Test public void theLiveProjectionFillsTheSameFacts() {
        LootDashboard.Drop drop = new LootDashboard.Drop("B.White", "Lost Halls", "Marble Colossus", 7_000, List.of(
            new LootDashboard.Item(20, "Item #20", "EQUIPMENT,ARMOR,ST", ParseEnchants.evidence(enchants(-1, 42, 7, 0)))));
        LootFacts.Bag bag = LootFacts.bag("live", drop);
        assertEquals("live", bag.session()); assertTrue(bag.white()); assertEquals("B.White", bag.bag());
        assertEquals("Lost Halls", bag.dungeon()); assertEquals("Marble Colossus", bag.dropper());
        assertEquals(new LootFacts.Item(20, false, true, false, false, 4, 3, EnchantInfo.of(enchants(-1, 42, 7, 0))), bag.items().get(0));
        LootFacts.Bag unknown = LootFacts.bag("live", new LootDashboard.Drop("Brown", "Unknown", "Unknown", 8_000, List.of(
            new LootDashboard.Item(21, "Potion #21", true))));
        assertNull("No map when the bag dropped: unknown area", unknown.dungeon()); assertNull(unknown.dropper());
        assertEquals(new LootFacts.Item(21, false, false, false, true, null, null), unknown.items().get(0));
    }

    /**
     * P6b Task 9 (R3 B2): one display label for an area. Capture saves "Unknown" when no map was known and the catalog's
     * "Unrecognized area" for a name it does not know; both, blank and none read "Unknown area". Display only: the saved keys stay.
     */
    @Test public void areaLabelSaysUnknownAreaForEveryUnknownFormAndKeepsNames() {
        for (String unknown : new String[] {null, "", " ", "Unknown", "Unrecognized area"}) {
            assertEquals("'" + unknown + "' reads as Unknown area", "Unknown area", LootFacts.areaLabel(unknown));
            assertNull("'" + unknown + "' is no known area", LootFacts.area(unknown));
        }
        assertEquals(LootFacts.UNKNOWN_AREA, LootFacts.areaLabel(null));
        for (String name : new String[] {"Lost Halls", "The Trials of Cronus", "Unknown Isle"}) {
            assertEquals(name, LootFacts.areaLabel(name));
            assertEquals(name, LootFacts.area(name));
        }
    }

    /**
     * The dungeon facet lists the saved keys ("Unknown" for bags without a map) and labels them for display: the list says "Unknown
     * area", the selection and the facets it applies keep "Unknown", and the facet summary says "Unknown area" too.
     */
    @Test public void theDungeonFacetLabelsUnknownAreaAndKeepsTheSavedKey() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LootFacetControls controls = new LootFacetControls(new LootQuery.Facets(), Set.of("White"), Set.of("Unknown", "Lost Halls"), next -> { });
            @SuppressWarnings("unchecked") JList<String> dungeons = (JList<String>) find(controls, "loot-dungeons-multi");
            List<String> keys = new ArrayList<>(), labels = new ArrayList<>();
            for (int i = 0; i < dungeons.getModel().getSize(); i++) {
                String key = dungeons.getModel().getElementAt(i);
                keys.add(key);
                JLabel cell = (JLabel) dungeons.getCellRenderer().getListCellRendererComponent(dungeons, key, i, false, false);
                labels.add(cell.getText());
                if ("Unknown".equals(key)) assertEquals("Spoken as shown", "Unknown area", cell.getAccessibleContext().getAccessibleName());
            }
            assertEquals("The model keeps the saved keys", List.of("Lost Halls", "Unknown"), keys);
            assertEquals("The list says Unknown area", List.of("Lost Halls", "Unknown area"), labels);
            dungeons.setSelectedIndex(keys.indexOf("Unknown"));
            assertEquals("The applied facet keeps the saved key", Set.of("Unknown"), controls.value().dungeons);
            controls.updateChoices(Set.of("White"), Set.of("Unknown", "Lost Halls", "Snake Pit"));
            JLabel cell = (JLabel) dungeons.getCellRenderer().getListCellRendererComponent(dungeons, "Unknown", 0, false, false);
            assertEquals("New choices keep the label", "Unknown area", cell.getText());
            assertEquals("…and the selection its key", Set.of("Unknown"), controls.value().dungeons);
        });
        LootQuery.Facets f = new LootQuery.Facets();
        f.dungeons.add("Unknown");
        f.dungeons.add("Lost Halls");
        String summary = LootFacetControls.summary(f);
        assertTrue(summary, summary.contains("Dungeons Unknown area, Lost Halls"));
        assertEquals("Summaries never change the facet", Set.of("Unknown", "Lost Halls"), f.dungeons);
    }

    private static java.awt.Component find(java.awt.Container root, String name) {
        for (java.awt.Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof java.awt.Container) { java.awt.Component found = find((java.awt.Container) child, name); if (found != null) return found; }
        }
        return null;
    }

    @Test public void potionStatNamesTheStatOfSmallGreaterAndSoulboundPotions() {
        String[] stats = {"Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"};
        int[][] ids = {{2793, 9070, 5471}, {2794, 9071, 5472}, {2591, 9064, 5465}, {2592, 9065, 5466},
            {2593, 9066, 5467}, {2636, 9069, 5470}, {2612, 9067, 5468}, {2613, 9068, 5469}};   // small, greater, soulbound
        for (int stat = 0; stat < stats.length; stat++)
            for (int id : ids[stat]) assertEquals("Potion " + id, stats[stat], LootFacts.potionStat(id));
        assertNull("Any other id is \"Other potions\"", LootFacts.potionStat(12_345));
        assertNull(LootFacts.potionStat(-1));
    }

    @Test public void enchantedNeedsTwoOrMoreKnownSlotsAndTheOldConstructorsMeanUnknown() {
        LootFacts.Item old = new LootFacts.Item(1, false, false, false, false);
        assertNull(old.slots()); assertNull(old.applied()); assertFalse(old.enchantKnown()); assertFalse(old.enchanted());
        LootFacts.Item legacy = new LootFacts.Item(1, false, false, false, false, -1, -1);
        assertNull("-1 is the legacy unknown", legacy.slots()); assertNull(legacy.applied()); assertEquals(old, legacy);
        assertEnchants(new LootFacts.Item(1, false, false, false, false, 0, 0), 0, 0, false);
        assertEnchants(new LootFacts.Item(1, false, false, false, false, 1, 1), 1, 1, false);
        assertEnchants(new LootFacts.Item(1, false, false, false, false, 2, 0), 2, 0, true);
        assertEnchants(new LootFacts.Item(1, false, false, false, false, 2, null), 2, null, true);
        assertEnchants(new LootFacts.Item(1, false, false, false, false, 4, 3), 4, 3, true);
        LootFacts.Bag bag = new LootFacts.Bag("s", 1, true, "White", null, List.of(old));
        assertNull("The old bag constructor: dungeon unknown", bag.dungeon()); assertNull("…and dropper unknown", bag.dropper());
        assertEquals(new LootFacts.Bag("s", 1, true, "White", null, List.of(old), null, null), bag);
    }

    private static void assertEnchants(LootFacts.Item item, int slots, Integer applied, boolean enchanted) {
        assertEquals("slots of " + item.id(), Integer.valueOf(slots), item.slots());
        assertEquals("applied of " + item.id(), applied, item.applied());
        assertTrue("known slots: enchant known", item.enchantKnown());
        assertEquals("enchanted = rare or better (2+ slots) for " + item, enchanted, item.enchanted());
    }

    /** An item's enchant code as the game sends it (ParseEnchantsSummaryTest's layout): -1 an empty unlocked slot, others applied IDs. */
    private static String enchants(int... slots) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + slots.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0).putShort((short) 1026);
        for (int slot : slots) buffer.putShort((short) slot);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }
}
