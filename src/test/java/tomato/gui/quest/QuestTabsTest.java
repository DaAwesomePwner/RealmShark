package tomato.gui.quest;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;
import org.junit.*;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class QuestTabsTest {
    private static final String ORDER = "ui.tabs.quests";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void savedPlansOpenByIdEvenWhenMovedAndHidden() throws Exception {
        PropertiesManager.setProperties(ORDER, "plans,captured|plans");
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = new QuestGUI(id -> "Item " + id, id -> null, new QuestGuiTest.MemoryPreferences());
            JTabbedPane tabs = find(ui, JTabbedPane.class, "quests-tabs");
            assertEquals(1, tabs.getTabCount()); assertEquals("Board", tabs.getTitleAt(0));
            ui.openPlans();
            assertEquals(Arrays.asList("Planner", "Board"), Arrays.asList(tabs.getTitleAt(0), tabs.getTitleAt(1)));
            assertEquals("Planner", tabs.getTitleAt(tabs.getSelectedIndex()));
            ui.openBoard();
            assertEquals("Board", tabs.getTitleAt(tabs.getSelectedIndex()));
        });
    }

    /** A pre-P4 saved order that hid the Board ("captured") keeps it hidden at startup; openBoard shows it by id and selects it. */
    @Test public void openBoardShowsAHiddenBoardById() throws Exception {
        PropertiesManager.setProperties(ORDER, "plans,captured|captured");
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = new QuestGUI(id -> "Item " + id, id -> null, new QuestGuiTest.MemoryPreferences());
            JTabbedPane tabs = find(ui, JTabbedPane.class, "quests-tabs");
            assertEquals(1, tabs.getTabCount()); assertEquals("Planner", tabs.getTitleAt(0));
            ui.openBoard();
            assertEquals(Arrays.asList("Planner", "Board"), Arrays.asList(tabs.getTitleAt(0), tabs.getTitleAt(1)));
            assertEquals("Board", tabs.getTitleAt(tabs.getSelectedIndex()));
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
