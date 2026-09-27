package tomato.gui.activity;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class ActivityCombatTabsTest {
    private static final String ORDER = "ui.tabs.activity-combat";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void resourceViewsFollowTheSavedOrderUnderTheirExistingName() throws Exception {
        PropertiesManager.setProperties(ORDER, "window,timeline,uptime|");
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                ActivityPanel combat = new ActivityPanel(log, ActivityPanel.Mode.COMBAT);
                JTabbedPane tabs = find(combat, JTabbedPane.class, "activity-resource-tabs");
                List<String> titles = new ArrayList<>(); for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
                assertEquals(Arrays.asList("Selected window", "Buff timeline & resources", "Uptime summary"), titles);
                assertNull("Runs has no resource views", find(new ActivityPanel(log, ActivityPanel.Mode.RUNS), JTabbedPane.class, "activity-resource-tabs"));
            });
        }
    }

    /** "Selected window" is saved as "primary", like the timeline; restoring it at startup must not un-hide the timeline. */
    @Test public void startupRestoreKeepsAHiddenTimelineHidden() throws Exception {
        tomato.gui.history.ArchiveNativeSupport.Memory memory = new tomato.gui.history.ArchiveNativeSupport.Memory();
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                PropertiesManager.setProperties(ORDER, "");
                ActivityPanel first = new ActivityPanel(log, ActivityPanel.Mode.COMBAT);
                first.bindViewState(memory.states);
                JTabbedPane tabs = find(first, JTabbedPane.class, "activity-resource-tabs");
                tabs.setSelectedIndex(tabs.indexOfTab("Selected window")); first.saveViewState();
                PropertiesManager.setProperties(ORDER, "timeline,uptime,window|timeline");
                ActivityPanel restored = new ActivityPanel(log, ActivityPanel.Mode.COMBAT);
                restored.bindViewState(memory.states);
                JTabbedPane shown = find(restored, JTabbedPane.class, "activity-resource-tabs");
                assertEquals("The hidden timeline stays hidden", -1, shown.indexOfTab("Buff timeline & resources"));
                assertEquals("timeline,uptime,window|timeline", PropertiesManager.getProperty(ORDER));
            });
            SwingUtilities.invokeAndWait(() -> {});
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
