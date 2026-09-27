package tomato.gui.logging;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class LoggingTabsTest {
    private static final String ORDER = "ui.tabs.logging";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void movedTabsKeepTheirTablesAndSavedStateKeys() throws Exception {
        PropertiesManager.setProperties(ORDER, "fields,discovery,reentry,packets,stats,events|");
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                LoggingGUI panel = new LoggingGUI(log, LoggingStateTestSupport.memoryStore());
                JTabbedPane tabs = find(panel, JTabbedPane.class, "logging-tabs");
                assertEquals(6, tabs.getTabCount()); assertEquals("Field catalog", tabs.getTitleAt(0));
                assertEquals("The restored default state selects Discovery by ID, wherever it sits", "Discovery", tabs.getTitleAt(tabs.getSelectedIndex()));
                assertEquals("discovery", panel.captureViewState().tab);
                tabs.setSelectedIndex(0); assertEquals("fields", panel.captureViewState().tab);
                tabs.setSelectedIndex(tabs.indexOfTab("Packets")); assertEquals("packets", panel.captureViewState().tab);
            });
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
