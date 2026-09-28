package tomato.gui.quest;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class QuestFilterBarTest {
    /** Spec §6.5: category labeling ("Name types…") lives in the Filters drawer; search, reset, sort and the Board's arrangement stay in the row. */
    @Test public void questFiltersAndNameTypesLiveInTheDrawerWhileSortAndGroupingStayVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = new QuestGUI(id -> "Item " + id, id -> null, new QuestGuiTest.MemoryPreferences());
            FilterBar bar = find(ui, FilterBar.class, "quests-filter-bar"); JComponent drawer = bar.drawerContent();
            for (String name : new String[]{"quest-type", "quest-reward", "quest-repeat-mode", "quest-requirement-item", "quest-pinned-only", "quest-completed",
                    "quest-name-types"})
                assertTrue(name, SwingUtilities.isDescendingFrom(find(ui, JComponent.class, name), drawer));
            for (String name : new String[]{"quest-search", "quest-reset", "quest-sort", "quest-group-by", "quest-pinned-first", "quest-view"}) {
                JComponent control = find(ui, JComponent.class, name);
                assertTrue(name, SwingUtilities.isDescendingFrom(control, bar)); assertFalse(name, SwingUtilities.isDescendingFrom(control, drawer));
            }
            find(ui, JComboBox.class, "quest-repeat-mode").setSelectedIndex(1); find(ui, AbstractButton.class, "quest-pinned-only").doClick();
            assertEquals(Arrays.asList("Repeatable", "Pinned only"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Pinned only"); assertFalse(find(ui, AbstractButton.class, "quest-pinned-only").isSelected());
            find(ui, AbstractButton.class, "quest-reset").doClick(); assertEquals(0, bar.activeCount());
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
