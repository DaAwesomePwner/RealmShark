package tomato.gui.security;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.*;
import static org.junit.Assert.*;

public class InspectFacetStateTest {
    @Test public void recordedRowsUseCaptureTimeAndObjectIdentityRatherThanNamesOnRefresh() throws Exception {
        RosterDefinitions definitions = RequirementResultTest.definitions();
        SwingUtilities.invokeAndWait(() -> {
            ParsePanelGUI panel = new ParsePanelGUI(false, () -> definitions);
            Player first = RequirementResultTest.player(1), second = RequirementResultTest.player(2);
            first.playerEntity.stat.get(packets.data.enums.StatType.NAME_STAT).stringStatValue = "SameName";
            second.playerEntity.stat.get(packets.data.enums.StatType.NAME_STAT).stringStatValue = "SameName";
            RequirementResultTest.put(first.playerEntity, 8, 42); RequirementResultTest.put(second.playerEntity, 8, 44);
            InspectSnapshot one = new InspectSnapshot(first.playerEntity, 1234), two = new InspectSnapshot(second.playerEntity, 5678);
            panel.showRun("exact-run", Arrays.asList(one, two)); JTable table = find(panel, JTable.class, null);
            assertEquals(2, table.getRowCount()); table.setRowSelectionInterval(0, 0);
            assertTrue(table.getValueAt(table.getSelectedRow(), 3).toString().contains("ID 42"));
            panel.showRun("exact-run", Arrays.asList(two, one));
            assertTrue(table.getValueAt(table.getSelectedRow(), 3).toString().contains("ID 42"));
            String evidence = find(panel, JTextArea.class, "inspect-requirement-reasons").getText();
            assertTrue(evidence.contains("Recorded snapshot")); assertFalse(evidence.contains("Current-area snapshot"));
            assertTrue(evidence.contains(tomato.gui.modern.DisplayFormat.formatTimestamp(1234L)));
        });
    }
    @Test public void displayFacetsDoNotChangeCopyScopeOrHistoricalLiveOwnership() throws Exception {
        RosterDefinitions definitions = RequirementResultTest.definitions();
        SwingUtilities.invokeAndWait(() -> {
            ParsePanelGUI live = new ParsePanelGUI(true, () -> definitions);
            SecurityFilter rules = RequirementResultTest.rules(); live.getFilters().put(rules.name, rules); ParsePanelGUI.currentFilter = rules; live.filterUpdate();
            Player pass = RequirementResultTest.player(1), below = RequirementResultTest.player(2);
            RequirementResultTest.put(pass.playerEntity, 8, 42);
            ParsePanelGUI.addPlayer(1, pass.playerEntity); ParsePanelGUI.addPlayer(2, below.playerEntity); live.refreshRoster();
            JTable table = find(live, JTable.class, null); assertEquals(2, table.getRowCount());
            JComboBox<?> verdict = find(live, JComboBox.class, "inspect-facet-4"); verdict.setSelectedIndex(2); live.refreshRoster();
            assertEquals(1, table.getRowCount()); assertEquals("Pass", table.getModel().getValueAt(0, 11));
            assertEquals(2, live.getFilteredPlayers().size());
            JCheckBox copy = find(live, JCheckBox.class, null); copy.setSelected(true);
            assertEquals(1, live.getFilteredPlayers().size()); assertEquals(2, live.getFilteredPlayers().get(0).playerEntity.id);
            ParsePanelGUI historical = new ParsePanelGUI(false, () -> definitions);
            historical.showRun("run", Collections.singleton(new InspectSnapshot(pass.playerEntity, 1000)));
            assertSame(rules, live.selectedFilter()); assertSame(rules, ParsePanelGUI.currentFilter);
            ParsePanelGUI.addPlayer(3, RequirementResultTest.player(3).playerEntity); live.refreshRoster();
            assertEquals(2, live.getFilteredPlayers().size());
            assertEquals(1, find(historical, JTable.class, null).getRowCount());
            ParsePanelGUI.clear();
        });
    }
    private static <T> T find(Container root, Class<T> type, String name) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && (name == null || name.equals(c.getName()))) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type, name); if (found != null) return found; }
        }
        return null;
    }
}
