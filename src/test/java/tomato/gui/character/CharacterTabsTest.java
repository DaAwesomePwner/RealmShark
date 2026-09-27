package tomato.gui.character;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.kit.DisplayModeModel;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class CharacterTabsTest {
    private static final String ORDER = "ui.tabs.character-detail";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder; private DisplayModeModel.Mode savedMode;
    @Before public void remember() throws Exception { savedOrder = PropertiesManager.getProperty(ORDER); SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode()); }
    @After public void restore() throws Exception {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
    }

    @Test public void snapshotEvidenceIsAnalystOnlyAndTabsKeepTheirSavedOrder() throws Exception {
        PropertiesManager.setProperties(ORDER, "goals,stats,equipment,exalts,notes,evidence,death|");
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty);
            JTabbedPane tabs = find(panel, JTabbedPane.class, "character-detail-tabs");
            assertEquals("Goals", tabs.getTitleAt(0)); assertEquals(6, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertEquals(7, tabs.getTabCount()); assertEquals(5, tabs.indexOfTab("Snapshot evidence"));
            tabs.setSelectedIndex(tabs.indexOfTab("Notes")); panel.openGoals();
            assertEquals("Goals", tabs.getTitleAt(tabs.getSelectedIndex()));
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
