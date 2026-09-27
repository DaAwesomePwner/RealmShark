package tomato.gui.security;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class InspectTableKindsTest {
    @After public void clearLivePlayers() throws Exception { SwingUtilities.invokeAndWait(ParsePanelGUI::clear); }

    @Test public void rosterAndRunTablesUseColumnKindWidths() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                ParsePanelGUI roster = new ParsePanelGUI(false);
                JTable players = first(roster);
                assertEquals(ColumnKind.CLASS.width(players.getFont()), players.getColumn("Class").getPreferredWidth());
                assertEquals("Equipment sprite cells keep their width", 75, players.getColumn("Weapon").getPreferredWidth());
                JTable runs = find(new InspectRunsPanel(log, roster), "inspect-runs-table");
                assertEquals(ColumnKind.DATE_TIME.width(runs.getFont()), runs.getColumnModel().getColumn(0).getPreferredWidth());
            });
        }
    }

    private static JTable first(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable) return (JTable) child;
            if (child instanceof Container) { JTable found = first((Container) child); if (found != null) return found; }
        }
        return null;
    }
    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
