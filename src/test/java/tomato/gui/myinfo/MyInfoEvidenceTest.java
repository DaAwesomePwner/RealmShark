package tomato.gui.myinfo;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import javax.swing.*;
import java.awt.*;
import static org.junit.Assert.*;

/** Component/model tests only: no native windows, focus, capture or HTTP. */
public class MyInfoEvidenceTest {
    private static final class View extends MyInfoGUI {
        String opened;
        View(TomatoData data) { super(data); }
        @Override protected void showMetricDetails(String title, String text) { opened = text; }
    }

    @Test public void unavailableCardsExplainInputsEvenWhenEveryTableRowIsFilteredOut() throws Exception {
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); data.player = player;
        stat(player, StatType.HP_STAT, 0);
        View[] view = new View[1];
        SwingUtilities.invokeAndWait(() -> { view[0] = new View(data); MyInfoGUI.updatePlayer(player); });
        SwingUtilities.invokeAndWait(() -> {
            find(view[0], JTextField.class, null).setText("no matching detail");
            assertEquals(0, find(view[0], JTable.class, null).getRowCount());
            find(view[0], JButton.class, "myinfo-metric-0").doClick();
            assertTrue(view[0].opened.contains("Health: 0 points"));
            assertTrue(view[0].opened.contains("Maximum health: Unavailable"));
            find(view[0], JButton.class, "myinfo-metric-3").doClick();
            assertTrue(view[0].opened.contains("wisdom not captured"));
            assertTrue(view[0].opened.contains("maximum mana not captured"));
            assertTrue(view[0].opened.contains("incomplete or malformed equipped enchant data"));
            assertTrue(view[0].opened.contains("pet metadata unknown"));
            String pinned = view[0].opened;
            find(view[0], JCheckBox.class, null).doClick();
            assertEquals(pinned, view[0].opened);
            assertTrue(view[0].metricDetails(3).contains("scenario: out of combat"));
            assertEquals(0, player.stat.get(StatType.HP_STAT).statValue);
        });
    }

    @Test public void evidenceFacetsKeepCapturedZeroAndKnownEstimatesSeparateFromUnavailable() throws Exception {
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); data.player = player;
        stat(player, StatType.MP_STAT, 0); stat(player, StatType.WISDOM_STAT, 50);
        View[] view = new View[1];
        SwingUtilities.invokeAndWait(() -> { view[0] = new View(data); MyInfoGUI.updatePlayer(player); });
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<?> facet = find(view[0], JComboBox.class, "myinfo-evidence");
            JTable table = find(view[0], JTable.class, null);
            facet.setSelectedItem("Captured");
            assertTrue(has(table, "Mana", 0d));
            assertFalse(has(table, "Estimated mana recovery", null));
            facet.setSelectedItem("Estimated");
            assertTrue(has(table, "Wisdom mana recovery", 6d));
            assertFalse(has(table, "Mana", 0d));
            facet.setSelectedItem("Unavailable");
            assertTrue(has(table, "Estimated mana recovery", null));
            assertFalse(has(table, "Mana", 0d));
        });
    }

    private static boolean has(JTable table, String name, Double value) {
        for (int i = 0; i < table.getRowCount(); i++)
            if (name.equals(table.getValueAt(i, 1)) && java.util.Objects.equals(value, table.getValueAt(i, 2))) return true;
        return false;
    }
    private static void stat(Entity entity, StatType type, int value) { StatData s = new StatData(); s.statValue = value; entity.stat.set(type, s); }
    private static <T> T find(Container root, Class<T> type, String name) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && (name == null || name.equals(c.getName()))) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type, name); if (found != null) return found; }
        }
        return null;
    }
}
