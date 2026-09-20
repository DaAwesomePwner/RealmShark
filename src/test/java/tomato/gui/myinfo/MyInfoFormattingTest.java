package tomato.gui.myinfo;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import static org.junit.Assert.*;
import static tomato.gui.modern.FormattingTestSupport.*;

public class MyInfoFormattingTest {
    @Test public void buildNumbersUseFormatLocaleButEquipmentIdsStayRawAndMissingIsNotZero() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); data.player = player;
        put(player, StatType.HP_STAT, 1234567); put(player, StatType.MP_STAT, 0);
        put(player, StatType.ATTACK_STAT, -1200); put(player, StatType.DEXTERITY_STAT, 9);
        put(player, StatType.INVENTORY_3_STAT, 12345);
        StatData dust = new StatData(); dust.stringStatValue = "0:NaN,1:0,2:0,3:0,4:Infinity";
        player.stat.set(StatType.DUST_STAT, dust);
        MyInfoGUI[] view = new MyInfoGUI[1];
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            SwingUtilities.invokeAndWait(() -> { view[0] = new MyInfoGUI(data); MyInfoGUI.updatePlayer(player); });
            SwingUtilities.invokeAndWait(() -> {
                JTable table = named(view[0], null, JTable.class);
                assertEquals("1,234,567", value(table, "Health"));
                assertEquals("—", value(table, "Maximum health"));
                assertEquals("0", value(table, "Mana"));
                assertEquals("-1,200", value(table, "Attack"));
                assertEquals("12345", value(table, "Ring"));
                assertEquals("—", value(table, "Green dust"));
                assertEquals("—", value(table, "Red dust"));
                assertEquals("0", value(table, "Purple dust"));
                assertEquals("1,234,567 / —", field(view[0], "summary", JLabel[].class)[0].getText());
            });
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            SwingUtilities.invokeAndWait(() -> MyInfoGUI.updatePlayer(player));
            SwingUtilities.invokeAndWait(() -> {
                JTable table = named(view[0], null, JTable.class);
                assertEquals("1.234.567", value(table, "Health"));
                assertEquals("-1.200", value(table, "Attack"));
                assertEquals("12345", value(table, "Ring"));
                assertEquals("1.234.567 / —", field(view[0], "summary", JLabel[].class)[0].getText());
                named(view[0], null, JComboBox.class).setSelectedItem("Character");
                assertEquals(Double.class, table.getColumnClass(2));
                table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(2, SortOrder.ASCENDING)));
                double last = Double.NEGATIVE_INFINITY;
                for (int row = 0; row < table.getRowCount(); row++) {
                    Double value = (Double)table.getValueAt(row, 2);
                    if (value != null) { assertTrue(value >= last); last = value; }
                }
            });
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    private static String value(JTable table, String name) {
        for (int row = 0; row < table.getRowCount(); row++) if (name.equals(table.getValueAt(row, 1))) return cell(table, row, 2);
        throw new AssertionError("Missing detail " + name);
    }
    private static void put(Entity entity, StatType type, int value) {
        StatData stat = new StatData(); stat.statValue = value; entity.stat.set(type, stat);
    }
}
