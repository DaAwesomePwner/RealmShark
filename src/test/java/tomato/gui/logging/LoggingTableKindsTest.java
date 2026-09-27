package tomato.gui.logging;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class LoggingTableKindsTest {
    @Test public void diagnosticTablesUseColumnKindWidthsAsTheirDefaults() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                LoggingGUI panel = new LoggingGUI(log, LoggingStateTestSupport.memoryStore());
                JTable packets = find(panel, "logging-table-0"), events = find(panel, "logging-table-2");
                assertEquals(ColumnKind.ID.width(packets.getFont()), packets.getColumn("ID").getPreferredWidth());
                assertEquals(ColumnKind.COUNT.width(packets.getFont()), packets.getColumn("Count").getPreferredWidth());
                assertEquals(ColumnKind.DATE_TIME.width(events.getFont()), events.getColumn("Time").getPreferredWidth());
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
