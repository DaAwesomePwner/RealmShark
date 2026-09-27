package tomato.gui.security;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.kit.DisplayModeModel;
import static org.junit.Assert.*;

public class InspectTabsTest {
    private DisplayModeModel.Mode savedMode;
    @Before public void remember() throws Exception { SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode()); }
    @After public void restore() throws Exception { SwingUtilities.invokeAndWait(() -> { DisplayModeModel.application().set(savedMode); ParsePanelGUI.clear(); }); }

    @Test public void abilityUseIsAnalystOnly() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                SecurityGUI panel = new SecurityGUI(log); JTabbedPane tabs = find(panel, JTabbedPane.class, "inspect-tabs");
                assertEquals(Arrays.asList("Current Area", "Runs"), titles(tabs));
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                assertEquals(Arrays.asList("Current Area", "Runs", "Ability Use"), titles(tabs));
                tabs.setSelectedIndex(2); DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                assertEquals("Hiding the selected Analyst tab falls back to the first tab", "Current Area", tabs.getTitleAt(tabs.getSelectedIndex()));
            });
            SwingUtilities.invokeAndWait(() -> { }); // lets the post-rebuild tab sync run before the log closes
        }
    }

    private static List<String> titles(JTabbedPane tabs) { List<String> titles = new ArrayList<>(); for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i)); return titles; }
    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
