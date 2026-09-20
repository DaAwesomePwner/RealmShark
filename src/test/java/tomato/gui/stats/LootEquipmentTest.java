package tomato.gui.stats;

import assets.IdToAsset;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.gui.modern.VioletTheme;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.LootBags;
import static org.junit.Assert.*;

public class LootEquipmentTest {
    @Test public void categoriesUseExactLabelsAndInclusiveTierThresholds() {
        assertTrue(item(1, "UT", "EQUIPMENT,WEAPON,UT,T13", "").ut);
        assertFalse(item(1, "UT", "EQUIPMENT,WEAPON,UT,T13", "").highTier);
        assertTrue(item(1, "ST", "EQUIPMENT,ARMOR,ST,T14", "").st);
        assertFalse(item(1, "ST", "EQUIPMENT,ARMOR,ST,T14", "").highTier);
        assertFalse(item(1, "UT ST in name", "EQUIPMENT,TAB_UT,ST_WEAPON,STATMOD", "").ut);
        assertFalse(item(1, "UT ST in name", "EQUIPMENT,TAB_UT,ST_WEAPON,STATMOD", "").st);
        for (String kind : new String[]{"WEAPON", "ARMOR"}) {
            assertFalse(item(1, "Below cutoff", "EQUIPMENT," + kind + ",T12", "").highTier);
            for (int tier : new int[]{13, 14, 15}) {
                LootDashboard.Item item = item(1, "Eligible", "EQUIPMENT," + kind + ",T" + tier, "");
                assertTrue(item.highTier); assertEquals("T" + tier, item.tier);
            }
        }
        assertFalse(item(1, "Below cutoff", "EQUIPMENT,ABILITY,T5", "").highTier);
        for (int tier : new int[]{6, 7, 8}) assertTrue(item(1, "Ability", "EQUIPMENT,ABILITY,T" + tier, "").highTier);
        assertFalse(item(1, "Ring", "EQUIPMENT,RING,T6", "").highTier);
        assertFalse(item(1, "Token", "EQUIPMENT,CONSUMABLE,ABILITY,T6", "").highTier);
        assertFalse(item(1, "Partial tier", "EQUIPMENT,WEAPON,T13_WEAPON", "").highTier);
        assertFalse(item(1, "Unknown", null, "").highTier);
        for (String kind : new String[]{"WEAPON", "ABILITY", "ARMOR", "RING"})
            assertTrue(item(1, "Wearable UT", "EQUIPMENT," + kind + ",UT", "").ut);
        for (String labels : new String[]{"EQUIPMENT,CONSUMABLE,UT", "EQUIPMENT,CONSUMABLE,STATPOTION,UT",
                "EQUIPMENT,UT", "EQUIPMENT,TOKEN,UT", "EQUIPMENT,SKIN,UT", "EQUIPMENT,ABILITY,CONSUMABLE,UT"})
            assertFalse("Exclude non-equipment: " + labels, item(1, "Non-tiered item", labels, "").ut);
    }

    @Test public void variantsAreCountedAcrossBagsWithIndependentFiltersAndRarityTotals() throws Exception {
        LootDashboard[] panels = new LootDashboard[2]; JFrame[] frames = new JFrame[2];
        LootDashboard.Item plain = item(910001, "Test blade", "EQUIPMENT,WEAPON,UT", "");
        LootDashboard.Item rare = item(910001, "Test blade", "EQUIPMENT,WEAPON,UT", encode(101, 102));
        LootDashboard.Item rareEmpty = item(910001, "Test blade", "EQUIPMENT,WEAPON,UT", encode(101, -1));
        LootDashboard.Item unknown = item(910001, "Test blade", "EQUIPMENT,WEAPON,UT", null);
        try {
            SwingUtilities.invokeAndWait(() -> {
                VioletTheme.install(); panels[0] = new LootDashboard(); panels[1] = new LootDashboard(panels[0]);
                panels[0].acceptAll(Arrays.asList(
                    drop("White", "Lost Halls", 1000, plain, plain, rare, rare,
                        item(910002, "Set armor", "EQUIPMENT,ARMOR,ST", encode(101)),
                        item(910003, "Tiered sword", "EQUIPMENT,WEAPON,T13", ""),
                        item(910004, "Potion", "STATPOTION,CONSUMABLE", "")),
                    drop("Orange", "The Shatters", 2000, rare, rareEmpty, unknown,
                        item(910002, "Set armor", "EQUIPMENT,ARMOR,ST", encode(101, 102, 103)),
                        item(910005, "Tiered robe", "EQUIPMENT,ARMOR,T13", encode(101, 102, 103, 104)),
                        item(910006, "Tiered ability", "EQUIPMENT,ABILITY,T6", encode(101, 102)),
                        item(910007, "Low weapon", "EQUIPMENT,WEAPON,T12", ""),
                        item(910008, "Low ability", "EQUIPMENT,ABILITY,T5", encode(101)),
                        item(910009, "Ring", "EQUIPMENT,RING,T6", encode(101, 102)))));
                frames[0] = show(panels[0]); frames[1] = show(panels[1]);
                assertArrayEquals(new int[]{2, 16}, panels[0].sessionTotals());
                JTable uts = table(panels[0], "UTs");
                assertEquals(4, uts.getRowCount()); assertEquals(7, count(uts));
                assertEquals("The Shatters", uts.getValueAt(0, 3)); assertEquals(3, uts.getValueAt(0, 2));
                assertEquals(2, uts.getValueAt(0, 6)); assertEquals(2, uts.getValueAt(0, 7));
                assertTrue(totals(panels[0]).contains("Unenchanted (0 slots): 2"));
                assertTrue(totals(panels[0]).contains("Rare (2): 4"));
                assertTrue(totals(panels[0]).contains("Unknown: 1"));
                assertEquals(2, table(panels[0], "STs").getRowCount());
                assertEquals(3, table(panels[0], "Tiered").getRowCount());
                assertEquals(16, count(table(panels[0], "All Items")));
                assertEquals(1, count(table(panels[0], "Stat Potions")));
                assertEquals(7, count(table(panels[0], "Whites")));
                JTable recent = table(panels[0], "Recent Drops");
                assertTrue(recent.getValueAt(0, 2).toString().contains("Rare, 2 slots, 1 enchants"));
                assertTrue(recent.getValueAt(0, 2).toString().contains("Unknown enchants"));
                table(panels[0], "UTs"); table(panels[1], "UTs");
                named(panels[0], "loot-search", JTextField.class).setText("Rare");
                assertEquals(2, uts.getRowCount()); assertEquals(4, count(uts));
                assertTrue(totals(panels[0]).startsWith("4 drops shown"));
                assertEquals(7, count(table(panels[1], "UTs")));
                named(panels[0], "loot-bag-filter", JComboBox.class).setSelectedItem("White");
                assertEquals(2, count(uts));
                named(panels[0], "loot-search", JTextField.class).setText("");
                assertEquals(4, count(uts));
                named(panels[0], "loot-dungeon-filter", JComboBox.class).setSelectedItem("The Shatters");
                assertEquals(0, count(uts)); assertTrue(totals(panels[0]).startsWith("0 drops shown"));
                named(panels[0], "loot-bag-filter", JComboBox.class).setSelectedItem("All bags");
                assertEquals(3, count(uts));
                named(panels[0], "loot-dungeon-filter", JComboBox.class).setSelectedItem("All dungeons");
                for (int width : new int[]{1050, 640}) for (String view : new String[]{"UTs", "STs", "Tiered"}) {
                    frames[0].setSize(width, 700); table(panels[0], view); frames[0].validate();
                    render(frames[0], "loot-" + view + "-" + width);
                }
            });
        } finally { SwingUtilities.invokeAndWait(() -> { for (JFrame frame : frames) if (frame != null) frame.dispose(); }); }
    }

    @Test public void utViewKeepsWearableGearWhileOtherTabsRetainRunesPotionsAndTokens() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LootDashboard panel = new LootDashboard(); JFrame frame = null;
            try {
                panel.accept(drop("White", "Oryx's Sanctuary", 1000,
                    item(920001, "UT weapon", "EQUIPMENT,WEAPON,UT", ""),
                    item(920002, "UT ability", "EQUIPMENT,ABILITY,UT", ""),
                    item(920003, "UT armor", "EQUIPMENT,ARMOR,UT", ""),
                    item(920004, "UT ring", "EQUIPMENT,RING,UT", ""),
                    item(920005, "Sword Rune", "EQUIPMENT,CONSUMABLE,UT", ""),
                    item(920006, "Stat potion", "EQUIPMENT,CONSUMABLE,STATPOTION,UT", ""),
                    item(920007, "Token", "EQUIPMENT,UT", ""),
                    item(920008, "Skin", "EQUIPMENT,SKIN,UT", "")));
                frame = show(panel);
                assertEquals(4, count(table(panel, "UTs")));
                assertEquals(8, count(table(panel, "All Items")));
                assertEquals(8, count(table(panel, "Whites")));
                assertEquals(1, count(table(panel, "Stat Potions")));
            } finally { if (frame != null) frame.dispose(); }
        });
    }

    @Test public void captureSnapshotsKeepEnchantFieldsAlignedWithInventorySlots() throws Exception {
        Field field = IdToAsset.class.getDeclaredField("objectID"); field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer, IdToAsset> assets = (Map<Integer, IdToAsset>)field.get(null);
        int id = 910011;
        IdToAsset old = assets.put(id, new IdToAsset("", id, "Capture UT", "", "Equipment", null, "", "EQUIPMENT,WEAPON,UT", ""));
        LootDashboard[] panel = new LootDashboard[1];
        try {
            SwingUtilities.invokeAndWait(() -> panel[0] = new LootDashboard());
            Entity bag = new Entity(null, 42, 0); bag.objectType = LootBags.ORANGE.getId();
            for (int slot : new int[]{0, 2, 4, 6, 7}) {
                StatData stat = new StatData(); stat.statValue = id;
                bag.stat.set(StatType.byOrdinal(StatType.INVENTORY_0_STAT.get() + slot), stat);
            }
            StatData unique = new StatData();
            unique.stringStatValue = "AAIE_f_9__3__f8=,," + encode(101, 102) + ",," + encode(-1, 102, -2) + ",,bad";
            bag.stat.set(StatType.UNIQUE_DATA_STRING, unique);
            panel[0].receive(null, bag, null, 1000);
            unique.stringStatValue = encode(101, 102, 103, 104);
            bag.stat.get(StatType.INVENTORY_0_STAT).statValue = -1;
            List<LootDashboard.Item> items = panel[0].recentDrops().get(0).items;
            assertEquals(5, items.size());
            for (LootDashboard.Item item : items) { assertTrue(item.ut); assertEquals("UT", item.tier); }
            assertEquals(0, items.get(0).enchants.slots);
            assertEquals(2, items.get(1).enchants.slots); assertEquals(2, items.get(1).enchants.applied);
            assertEquals(2, items.get(2).enchants.slots); assertEquals(1, items.get(2).enchants.applied);
            assertEquals(-1, items.get(3).enchants.slots); assertEquals(-1, items.get(4).enchants.slots);
        } finally { if (old == null) assets.remove(id); else assets.put(id, old); }
    }

    @Test public void enchantVariantTotalsSurviveRecentHistoryEviction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LootDashboard panel = new LootDashboard(); JFrame frame = null;
            try {
                List<LootDashboard.Drop> drops = new ArrayList<>();
                for (int i = 0; i < 1102; i++) drops.add(drop("White", "Lost Halls", i,
                    item(910020, "Test UT", "EQUIPMENT,WEAPON,UT", i % 2 == 0 ? "" : encode(101, 102))));
                panel.acceptAll(drops); frame = show(panel);
                assertEquals(1000, panel.recentDrops().size());
                JTable table = table(panel, "UTs");
                assertEquals(2, table.getRowCount()); assertEquals(1102, count(table));
                assertTrue(totals(panel).contains("Rare (2): 551"));
                assertTrue(totals(panel).contains("Unenchanted (0 slots): 551"));
                table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(6, SortOrder.ASCENDING)));
                assertEquals(0, table.getValueAt(0, 6)); assertEquals(2, table.getValueAt(1, 6));
            } finally { if (frame != null) frame.dispose(); }
        });
    }

    static String encode(int... entries) {
        ByteBuffer buffer = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte)0).putShort((short)1026);
        for (int i = 0; i < 4; i++) buffer.putShort((short)(i < entries.length ? entries[i] : -3));
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }
    private static LootDashboard.Item item(int id, String name, String labels, String code) {
        return new LootDashboard.Item(id, name, labels, ParseEnchants.summarize(code));
    }
    private static LootDashboard.Drop drop(String bag, String dungeon, long time, LootDashboard.Item... items) {
        return new LootDashboard.Drop(bag, dungeon, "Boss", time, Arrays.asList(items));
    }
    private static int count(JTable table) { int count = 0; for (int i = 0; i < table.getRowCount(); i++) count += (Integer)table.getValueAt(i, 2); return count; }
    private static String totals(LootDashboard panel) { return named(panel, "loot-enchant-totals", JTextArea.class).getText(); }
    private static JTable table(LootDashboard panel, String name) {
        JTabbedPane views = named(panel, "loot-views", JTabbedPane.class); int index = views.indexOfTab(name);
        assertTrue("Missing " + name, index >= 0); views.setSelectedIndex(index);
        return named(panel, "loot-view-" + index, JTable.class);
    }
    private static JFrame show(LootDashboard panel) {
        JFrame frame = new JFrame("Loot equipment preview"); frame.setContentPane(panel);
        frame.setSize(1050, 700); frame.setVisible(true); frame.validate(); return frame;
    }
    private static void render(JFrame frame, String name) {
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); frame.printAll(graphics); graphics.dispose();
        try { File dir = new File("screenshots"); dir.mkdirs(); ImageIO.write(image, "png", new File(dir, name + ".png")); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = named((Container)child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
