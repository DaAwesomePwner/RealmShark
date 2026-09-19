package tomato.gui.maingui;

import org.junit.Test;
import tomato.realmshark.ParseEnchants;
import util.PropertiesManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class RuleEditorActionsTest {
    @Test public void keyboardAddAndRemoveKeepTheEditedValueAndSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomListGUI panel = new CustomListGUI(null, "test-rules", "Test rules",
                    new ArrayList<>(Arrays.asList("One", "Two")), "Rule") {
                @Override protected boolean validateEntry(String text) { return true; }
            };
            try {
                JTable table = find(panel, JTable.class);
                table.setRowSelectionInterval(0, 0);
                assertNotNull(button(panel, "Add rule"));
                assertNotNull(button(panel, "Remove selected rule"));
                table.getActionMap().get("add-rule").actionPerformed(new ActionEvent(table, 0, "keyboard"));
                assertEquals(3, table.getRowCount());
                assertTrue(table.isEditing());
                ((JTextField) table.getEditorComponent()).setText("Three");
                assertTrue(table.getCellEditor().stopCellEditing());
                assertEquals("Three", table.getValueAt(2, 0));
                Object remove = table.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
                assertNotNull(remove);
                table.getActionMap().get(remove).actionPerformed(new ActionEvent(table, 0, "keyboard"));
                assertEquals(2, table.getRowCount());
                assertEquals(1, table.getSelectedRow());
                assertEquals("Two", table.getValueAt(1, 0));
            } finally {
                Window dialog = SwingUtilities.getWindowAncestor(panel);
                if (dialog != null) dialog.dispose();
            }
        });
    }

    @Test public void groupToggleRetainsControlsAndSearchRestoresExpansionWithoutLosingSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<Short, String> old = new HashMap<>(ParseEnchants.ENCHANTS);
            String preference = PropertiesManager.getProperty("enchantPing.selected");
            try {
                ParseEnchants.ENCHANTS.clear();
                ParseEnchants.ENCHANTS.put((short) 1, "Agile");
                ParseEnchants.ENCHANTS.put((short) 2, "Brisk");
                PropertiesManager.setProperties("enchantPing.selected", "");
                EnchantPingGUI panel = new EnchantPingGUI(null);
                JButton toggle = button(panel, "▶ A");
                assertNotNull(toggle);
                assertTrue(toggle.isFocusPainted());
                assertTrue(toggle.isFocusable());
                assertEquals("Toggle enchant group A", toggle.getAccessibleContext().getAccessibleName());
                pressSpace(toggle);
                assertSame(toggle, button(panel, "▼ A"));
                JCheckBox checkBox = find(panel, JCheckBox.class);
                checkBox.setSelected(true);
                pressSpace(toggle);
                assertSame(toggle, button(panel, "▶ A"));
                JTextField search = find(panel, JTextField.class);
                search.setText("Agile");
                assertSame(toggle, button(panel, "▼ A"));
                search.setText("");
                assertSame(toggle, button(panel, "▶ A"));
                assertEquals(Arrays.asList("Agile(1)"), panel.getSelectedItems());
                assertSame(checkBox, find(panel, JCheckBox.class));
            } finally {
                ParseEnchants.ENCHANTS.clear();
                ParseEnchants.ENCHANTS.putAll(old);
                PropertiesManager.setProperties("enchantPing.selected", preference == null ? "" : preference);
            }
        });
    }

    private static void pressSpace(JButton button) {
        for (boolean release : new boolean[]{false, true}) {
            Object key = button.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, release));
            button.getActionMap().get(key).actionPerformed(new ActionEvent(button, 0, "keyboard"));
        }
    }

    private static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton) c).getText())) return (JButton) c;
            if (c instanceof Container) { JButton found = button((Container) c, text); if (found != null) return found; }
        }
        return null;
    }

    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container) c, type); if (found != null) return found; }
        }
        return null;
    }
}
