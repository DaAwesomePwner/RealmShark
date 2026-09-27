package tomato.gui.dps;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class TableKindsTest {
    @Test public void meterAndLiveRunTablesUseColumnKindWidths() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                JTable meter = find(new MeterDpsGUI(), "dps-player-table");
                assertEquals(ColumnKind.CLASS.width(meter.getFont()), meter.getColumn("Class").getPreferredWidth());
                assertEquals(ColumnKind.NUMBER.width(meter.getFont()), meter.getColumn("Damage").getPreferredWidth());
                JTable runs = find(new ActivityPanel(log, ActivityPanel.Mode.RUNS), "activity-table");
                assertEquals(ColumnKind.DUNGEON.width(runs.getFont()), runs.getColumnModel().getColumn(0).getPreferredWidth());
                assertEquals(ColumnKind.DATE_TIME.width(runs.getFont()), runs.getColumnModel().getColumn(1).getPreferredWidth());
            });
        }
    }

    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
