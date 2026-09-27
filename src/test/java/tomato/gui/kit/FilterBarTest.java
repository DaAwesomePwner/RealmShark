package tomato.gui.kit;

import java.util.*;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;

public class FilterBarTest {
    @Test public void rebuildingDuringLoadKeepsNewChipsAndFacetsDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FilterBar bar = new FilterBar("loading", k -> null, (k, v) -> {});
            JButton old = new JButton("Old"); old.setEnabled(false);
            bar.drawer(old); bar.setDrawerEnabled(false);
            JButton replacement = new JButton("New");
            bar.drawer(replacement);
            int[] edits = {0};
            bar.setActive(Collections.singletonList(new FilterBar.ActiveFilter("New filter", () -> edits[0]++)), () -> edits[0]++);
            assertFalse(replacement.isEnabled());
            AbstractButton remove = ControlsTest.find(bar, "remove-filter");
            assertFalse(remove.isEnabled()); remove.doClick();
            assertEquals(0, edits[0]);
            assertFalse("Detached control keeps its original disabled state", old.isEnabled());
            bar.setDrawerEnabled(true);
            assertTrue(replacement.isEnabled()); assertTrue(remove.isEnabled());
        });
    }

    @Before public void noMotion() { Motion.systemOverride = false; }
    @After public void restore() { Motion.systemOverride = null; }

    @Test public void activeFiltersBecomeRemovableChipsWithACount() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            FilterBar bar = new FilterBar("runs", store::get, store::put);
            bar.search(new JTextField(12)).drawer(new JLabel("Outcome, evidence, dates"));
            List<String> removed = new ArrayList<>(); int[] cleared = {0};
            bar.setActive(Arrays.asList(new FilterBar.ActiveFilter("Completed", () -> removed.add("outcome")),
                new FilterBar.ActiveFilter("Last 7 days", () -> removed.add("dates"))), () -> cleared[0]++);
            AbstractButton filters = ControlsTest.find(bar, "runs-filters");
            assertEquals("Filters · 2", filters.getText());
            assertEquals(2, bar.activeCount());
            ControlsTest.find(bar, "remove-filter").doClick();
            assertEquals(Collections.singletonList("outcome"), removed);
            AbstractButton clear = ControlsTest.find(bar, "runs-clear-filters");
            assertTrue(clear.isVisible());
            clear.doClick();
            assertEquals(1, cleared[0]);
            bar.setActive(Collections.emptyList(), null);
            assertEquals("Filters", filters.getText());
            assertFalse(clear.isVisible());
        });
    }

    @Test public void drawerIsClosedByDefaultAndRemembered() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            JLabel facets = new JLabel("Facets");
            FilterBar bar = new FilterBar("loot", store::get, store::put).drawer(facets);
            assertFalse(bar.drawerOpen());
            assertFalse("The drawer panel is hidden while closed", facets.getParent().isVisible());
            ControlsTest.find(bar, "loot-filters").doClick();
            assertTrue(bar.drawerOpen());
            assertEquals("true", store.get("ui.filters.loot.open"));
            assertTrue(new FilterBar("loot", store::get, store::put).drawer(new JLabel()).drawerOpen());
        });
    }

    @Test public void withoutADrawerTheFiltersToggleIsHidden() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FilterBar bar = new FilterBar("chat", k -> null, (k, v) -> {});
            assertFalse(ControlsTest.find(bar, "chat-filters").isVisible());
            bar.setDrawerOpen(true);
            assertFalse("Cannot open an empty drawer", bar.drawerOpen());
        });
    }

    @Test public void drawerContentCanBeDisabledWhileAQueryLoads() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel facets = new JPanel(); JButton apply = new JButton("Apply"); facets.add(apply);
            FilterBar bar = new FilterBar("keypops", k -> null, (k, v) -> {}).drawer(facets);
            bar.setDrawerEnabled(false);
            assertFalse(apply.isEnabled());
            bar.setDrawerEnabled(true);
            assertTrue(apply.isEnabled());
        });
    }
}
