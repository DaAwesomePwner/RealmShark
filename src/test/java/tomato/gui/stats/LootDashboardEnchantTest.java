package tomato.gui.stats;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.Test;
import tomato.gui.kit.ItemIcon;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;

/** Dashboard rows: single drops show their exact enchants, merged rows only the shared rarity; icon tooltips are not escaped. */
public class LootDashboardEnchantTest {
    private static final Icon BASE = new ImageIcon(new java.awt.image.BufferedImage(24, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB));

    @Test public void aSingleDropShowsItsExactEnchantsAndAMergedRowOnlyItsRarity() {
        LootDashboard.Item item = new LootDashboard.Item(10, "Doom Bow", "EQUIPMENT,WEAPON,UT", ParseEnchants.evidence(enchants(-1, 42)));
        ItemIcon single = LootDashboard.rowIcon(BASE, "Doom Bow", 1, item);
        assertSame("The cached base icon is reused, never replaced", BASE, single.base());
        assertEquals(item.enchantInfo(), single.enchant());
        assertTrue(single.tooltip(), single.tooltip().startsWith("<html><b>Doom Bow · UT</b>"));
        LootDashboard.Item unknownTier = new LootDashboard.Item(11, "Unknown bow", "EQUIPMENT,WEAPON", ParseEnchants.evidence(enchants(-1, 42)));
        ItemIcon unknown = LootDashboard.rowIcon(BASE, "Unknown bow", 1, unknownTier);
        assertTrue(unknown.tooltip(), unknown.tooltip().startsWith("<html><b>Unknown bow</b>"));
        ItemIcon merged = LootDashboard.rowIcon(BASE, "Doom Bow", 3, item);
        assertEquals(EnchantInfo.State.COUNT_ONLY, merged.enchant().state());
        assertEquals(EnchantInfo.Rarity.RARE, merged.enchant().rarity());
        assertTrue(merged.tooltip(), merged.tooltip().startsWith("<html><b>Doom Bow · 3 drops</b>"));
        assertFalse("One drop's names are never shown for all three", merged.tooltip().contains("(empty slot)"));
    }

    @Test public void mergedPotionRowsHaveNoEnchantTooltip() {
        LootDashboard.Item potion = new LootDashboard.Item(30, "Potion of Life", "STATPOTION", ParseEnchants.summarize(""));
        assertSame(EnchantInfo.notRecorded(), LootDashboard.rowIcon(BASE, "Potion of Life", 3, potion).enchant());
        assertNull(LootDashboard.rowIcon(BASE, "Potion of Life", 3, potion).tooltip());
        assertNull("…nor a single-drop potion row", LootDashboard.rowIcon(BASE, "Potion of Life", 1, potion).tooltip());
    }

    @Test public void iconTooltipsReachTheUserUnescapedWhileTextStaysEscaped() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[][] {
                {new ItemIcon(BASE, "Doom Bow", EnchantInfo.ofSlotCount(2)), "<b>not markup</b>", BASE}}, new Object[] {"Icon", "Item", "Plain"}) {
                @Override public Class<?> getColumnClass(int column) { return column == 1 ? String.class : Icon.class; }
            };
            JTable table = StatsUi.table(model, "loot-test");
            table.setSize(600, 200); table.doLayout();
            assertTrue(tip(table, 0).startsWith("<html><b>Doom Bow</b>"));
            assertTrue(tip(table, 0).contains("Rare · 2 enchant slots"));
            assertTrue("Text cells stay escaped", tip(table, 1).contains("&lt;b&gt;"));
            assertNull("Plain icons still have no tooltip", tip(table, 2));
        });
    }

    private static String tip(JTable table, int column) {
        Rectangle cell = table.getCellRect(0, column, true);
        Point at = new Point(cell.x + cell.width / 2, cell.y + cell.height / 2);
        return table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, at.x, at.y, 0, false));
    }

    private static String enchants(int... slots) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + slots.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0); buffer.putShort((short) 1026);
        for (int slot : slots) buffer.putShort((short) slot);
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }
}
