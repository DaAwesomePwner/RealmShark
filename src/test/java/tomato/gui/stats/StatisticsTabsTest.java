package tomato.gui.stats;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import tomato.backend.data.TomatoData;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class StatisticsTabsTest {
    private static final String ORDER = "ui.tabs.statistics";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void tooltipsFollowTheirTabsWhenReordered() throws Exception {
        PropertiesManager.setProperties(ORDER, "loot,fame-graph,fame-table,dungeon-stats|");
        SwingUtilities.invokeAndWait(() -> {
            StatisticsGUI panel = new StatisticsGUI(new TomatoData());
            JTabbedPane tabs = find(panel, JTabbedPane.class, "statistics-tabs");
            assertEquals("Loot", tabs.getTitleAt(0));
            assertEquals("Shared session loot summaries and original drop log", tabs.getToolTipTextAt(0));
            assertEquals("Character and time-range filters with interval comparison", tabs.getToolTipTextAt(tabs.indexOfTab("Fame Graph")));
        });
    }

    @Test public void selectionKeepsItsCanonicalMeaningAcrossOrderChanges() throws Exception {
        tomato.gui.history.ArchiveNativeSupport.Memory memory = new tomato.gui.history.ArchiveNativeSupport.Memory();
        SwingUtilities.invokeAndWait(() -> {
            PropertiesManager.setProperties(ORDER, "loot,fame-graph,fame-table,dungeon-stats|");
            tomato.gui.kit.CustomizableTabs first = sampleTabs();
            first.select("loot");
            new StatisticsLiveState(memory.states, "statistics-live").tabs(first,"fame-graph","fame-table","loot","dungeon-stats");
            assertEquals("Without saved selection, keep the current tab", "loot", first.selectedId());
            first.select("fame-graph");
            first.select("loot"); first.move("loot", 1);
            PropertiesManager.setProperties(ORDER, "dungeon-stats,fame-table,fame-graph,loot|");
            tomato.gui.kit.CustomizableTabs restored = sampleTabs();
            new StatisticsLiveState(memory.states, "statistics-live").tabs(restored,"fame-graph","fame-table","loot","dungeon-stats");
            assertEquals("loot", restored.selectedId());
            // Tab hiding persists: the saved selection never un-hides a tab at startup.
            PropertiesManager.setProperties(ORDER, "dungeon-stats,fame-table,fame-graph,loot|loot");
            tomato.gui.kit.CustomizableTabs hidden = sampleTabs();
            new StatisticsLiveState(memory.states, "statistics-live").tabs(hidden,"fame-graph","fame-table","loot","dungeon-stats");
            assertFalse("The hidden Loot tab stays hidden", hidden.visibleIds().contains("loot"));
            assertTrue("A visible tab stays selected", hidden.visibleIds().contains(hidden.selectedId()));
            assertEquals("dungeon-stats,fame-table,fame-graph,loot|loot", PropertiesManager.getProperty(ORDER));
        });
    }

    private static tomato.gui.kit.CustomizableTabs sampleTabs() {
        return new tomato.gui.kit.CustomizableTabs("statistics")
            .add("fame-graph","Fame Graph",new JPanel()).add("fame-table","Fame Table",new JPanel())
            .add("loot","Loot",new JPanel()).add("dungeon-stats","Dungeon Stats",new JPanel());
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
