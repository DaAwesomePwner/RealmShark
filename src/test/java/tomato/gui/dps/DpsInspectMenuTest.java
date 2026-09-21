package tomato.gui.dps;

import org.junit.After;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.security.SecurityFilter;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import static org.junit.Assert.*;

public class DpsInspectMenuTest {
    private JFrame frame;
    private final SecurityFilter previousFilter = ParsePanelGUI.currentFilter;

    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (frame != null) { for (Window owned : frame.getOwnedWindows()) owned.dispose(); frame.dispose(); }
            ParsePanelGUI.currentFilter = previousFilter;
        });
    }

    @Test public void rightClickInspectsTheSortedClickedPlayerAndPinsTheirBuildAcrossLiveRefreshes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Entity alpha = player(1, "Alpha", 12345), beta = player(2, "Beta", 67890);
            Entity enemy = enemy(alpha, beta);
            MeterDpsGUI meter = new MeterDpsGUI(); show(meter);
            meter.renderData(map("First dungeon"), Collections.singletonList(enemy), new ArrayList<>(), 2000, true);
            JTable table = find(meter, JTable.class, "dps-player-table");
            assertEquals("Beta", table.getValueAt(0, 0));
            table.setRowSelectionInterval(0, 0);
            rightClick(table, 1);
            JPopupMenu menu = table.getComponentPopupMenu();
            assertTrue(menu.isVisible()); assertEquals(1, table.getSelectedRow());
            JMenuItem inspect = (JMenuItem)menu.getComponent(0); assertEquals("Inspect", inspect.getText());

            stat(alpha, StatType.NAME_STAT, 0, "Changed after click");
            stat(alpha, StatType.INVENTORY_0_STAT, 54321, ""); alpha.baseStats[0] = 1;
            meter.renderData(map("New live snapshot"), Collections.singletonList(enemy), new ArrayList<>(), 3000, true);
            inspect.doClick();
            JTextArea details = details();
            assertFalse(details.isEditable());
            assertTrue(details.getText().contains("Player: Alpha"));
            assertTrue(details.getText().contains("Level: 20"));
            assertTrue(details.getText().contains("Guild: Captured guild"));
            assertTrue(details.getText().contains("Seasonal · Crucible"));
            assertTrue(details.getText().contains("HP: 555"));
            assertTrue(details.getText().contains("ID 12345"));
            assertTrue(details.getText().contains("Enchants: None (captured)"));
            assertTrue(details.getText().contains("Ring: Not captured"));
            assertFalse(details.getText().contains("Changed after click"));
            assertFalse(details.getText().contains("54321"));
            String pinned = details.getText();
            meter.renderData(map("Another encounter"), Collections.emptyList(), new ArrayList<>(), 0, true);
            assertEquals(pinned, details.getText());
        });
    }

    @Test public void savedInspectionIgnoresTheLiveInspectRosterAndBlankSpaceHasNoStaleAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            new ParsePanelGUI();
            ParsePanelGUI.addPlayer(1, player(1, "Alpha", 99999));
            SecurityFilter filter = new SecurityFilter(); filter.name = "Keep my filter"; ParsePanelGUI.currentFilter = filter;
            Entity savedPlayer = player(1, "Alpha", 12345), other = player(2, "Beta", 67890);
            MeterDpsGUI meter = new MeterDpsGUI(); show(meter);
            meter.renderData(map("Saved dungeon"), Collections.singletonList(enemy(savedPlayer, other)), new ArrayList<>(), 2000, false);
            JTable table = find(meter, JTable.class, "dps-player-table");
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
            table.moveColumn(0, 2);
            table.setRowSelectionInterval(0, 0);
            KeyStroke key = KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK);
            Object action = table.getInputMap().get(key); assertEquals("inspect-player-menu", action);
            table.getActionMap().get(action).actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "keyboard"));
            JPopupMenu menu = table.getComponentPopupMenu(); assertTrue(menu.isVisible());
            ((JMenuItem)menu.getComponent(0)).doClick();
            assertTrue(details().getText().contains("ID 12345")); assertFalse(details().getText().contains("99999"));
            assertSame(filter, ParsePanelGUI.currentFilter);
            MenuSelectionManager.defaultManager().clearSelectedPath(); menu.setVisible(false);
            menu.show(table, 8, table.getRowHeight() * table.getRowCount() + 10);
            assertFalse(menu.isVisible()); assertFalse(menu.getComponent(0).isEnabled());
            assertEquals(-1, table.getSelectedRow());
        });
    }

    @Test public void legacyPlayerRowChildrenInheritTheInspectMenu() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel row = new JPanel(); JPanel equipment = new JPanel(); JLabel name = new JLabel("Alpha");
            equipment.add(name); row.add(equipment);
            PlayerInspectMenu.install(row, player(1, "Alpha", 12345)); show(row);
            assertTrue(name.getInheritsPopupMenu()); assertSame(row.getComponentPopupMenu(), name.getComponentPopupMenu());
            name.getComponentPopupMenu().show(name, 2, 2);
            ((JMenuItem)name.getComponentPopupMenu().getComponent(0)).doClick();
            assertTrue(details().getText().contains("Player: Alpha")); assertTrue(details().getText().contains("ID 12345"));
        });
    }

    private void show(JComponent panel) { frame = new JFrame(); frame.setContentPane(panel); frame.setSize(1050, 720); frame.setVisible(true); frame.validate(); }
    private static void rightClick(JTable table, int row) {
        Rectangle cell = table.getCellRect(row, 0, true);
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                0, cell.x + 8, cell.y + cell.height / 2, 1, true, MouseEvent.BUTTON3));
    }
    private JTextArea details() {
        for (Window window : frame.getOwnedWindows()) if (window instanceof JDialog && window.isShowing()) {
            JTextArea body = find((Container)window, JTextArea.class, "security-equipment-details");
            if (body != null) return body;
        }
        throw new AssertionError("Inspect must open an owned character-details dialog");
    }
    private static <T> T find(Container root, Class<T> type, String name) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T result = find((Container)c, type, name); if (result != null) return result; }
        }
        return null;
    }
    private static MapInfoPacket map(String name) { MapInfoPacket map = new MapInfoPacket(); map.name = name; return map; }
    private static Entity enemy(Entity alpha, Entity beta) {
        Entity enemy = new Entity(null, 100, 0); enemy.objectType = 123;
        enemy.getDamageList().add(new Damage(alpha, 1000, 100)); enemy.getDamageList().add(new Damage(beta, 2000, 1000));
        enemy.updateDamageTaken(1000); enemy.updateDamageTaken(2000); return enemy;
    }
    private static Entity player(int id, String name, int weapon) {
        Entity player = new Entity(null, id, 0); player.objectType = 99998; player.markPlayerIdentity();
        player.baseStats = new int[]{555, 200, 50, 25, 50, 60, 40, 40};
        stat(player, StatType.NAME_STAT, 0, name); stat(player, StatType.GUILD_NAME_STAT, 0, "Captured guild");
        stat(player, StatType.LEVEL_STAT, 20, ""); stat(player, StatType.SEASONAL, 1, ""); stat(player, StatType.CRUCIBLE_STAT, 0, "active");
        stat(player, StatType.INVENTORY_0_STAT, weapon, ""); stat(player, StatType.INVENTORY_1_STAT, -1, ""); stat(player, StatType.INVENTORY_2_STAT, 777, "");
        stat(player, StatType.UNIQUE_DATA_STRING, 0, ""); return player;
    }
    private static void stat(Entity player, StatType type, int number, String text) {
        StatData value = new StatData(); value.statType = type; value.statTypeNum = type.get(); value.statValue = number; value.stringStatValue = text;
        player.stat.set(type, value);
    }
}
