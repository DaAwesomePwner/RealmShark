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
            assertEquals(1, tabs.getTabCount()); assertEquals("Captured quests", tabs.getTitleAt(0));
            ui.openPlans();
            assertEquals(Arrays.asList("Saved plans", "Captured quests"), Arrays.asList(tabs.getTitleAt(0), tabs.getTitleAt(1)));
            assertEquals("Saved plans", tabs.getTitleAt(tabs.getSelectedIndex()));
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
