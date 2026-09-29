package tomato.gui.security;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class InspectRosterFilterBarTest {
    /** P6b: the live roster's view state is two ⋯ items with a failure-only status; a saved run's roster has no ⋯ at all. */
    @Test public void liveViewStateIsInTheRowsOverflowWithAFailureOnlyStatusAndTheFooterUsesKitButtons() throws Exception {
        tomato.gui.roster.RosterStateTestSupport.Memory memory = new tomato.gui.roster.RosterStateTestSupport.Memory();
        String before = util.PropertiesManager.getProperty("ui.filters.inspect-roster.open");
        try {
            ParsePanelGUI[] live = new ParsePanelGUI[1];
            SwingUtilities.invokeAndWait(() -> {
                tomato.backend.data.RosterDefinitions definitions = tomato.backend.data.RosterDefinitions.empty();
                live[0] = new ParsePanelGUI(true, () -> definitions); live[0].bindViewState(memory.store);
                FilterBar bar = find(live[0], FilterBar.class, "inspect-roster-filter-bar");
                assertTrue("The roster's ⋯ shows", bar.overflow().isVisible());
                assertEquals(Arrays.asList("inspect-live-roster-save-state", "inspect-live-roster-reset-state"), itemNames(bar));
                JComponent banner = find(live[0], JComponent.class, "inspect-live-roster-view-state");
                assertFalse("No status row while nothing failed", banner.isVisible());
                for (String label : new String[]{"Copy names", "Copy all (JSON)", "Actions…"})
                    assertTrue(label + " is a kit button", button(live[0], label) instanceof tomato.gui.kit.KitButton);
                assertTrue("Requirement details stays a toggle", button(live[0], "Requirement details") instanceof JToggleButton);
                assertTrue(button(live[0], "Reset display filters") instanceof tomato.gui.kit.KitButton);
                memory.fail = true; PartyRestyleTest.item(bar, "inspect-live-roster-save-state").doClick(0);
            });
            SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> {
                tomato.gui.kit.Banner banner = find(live[0], tomato.gui.kit.Banner.class, "inspect-live-roster-view-state");
                assertTrue("A failed save shows its status", banner.isVisible()); assertTrue(banner.warns());
                assertTrue(banner.text(), banner.text().contains("failed"));
                ParsePanelGUI saved = new ParsePanelGUI(false);
                FilterBar savedBar = find(saved, FilterBar.class, "inspect-roster-filter-bar");
                assertTrue("A saved run's roster has no view state", itemNames(savedBar).isEmpty());
                assertFalse("…so its ⋯ stays hidden", savedBar.overflow().isVisible());
                ParsePanelGUI.clear();
            });
        } finally { util.PropertiesManager.setProperties("ui.filters.inspect-roster.open", before == null ? "" : before); }
    }

    private static java.util.List<String> itemNames(FilterBar bar) {
        java.util.List<String> names = new ArrayList<>();
        for (Component c : bar.overflow().menu().getComponents()) if (c instanceof JMenuItem) names.add(c.getName());
        return names;
    }

    @Test public void displayFacetsAndCopyOptionsLiveInTheDrawer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ParsePanelGUI roster = new ParsePanelGUI(false);
            FilterBar bar = find(roster, FilterBar.class, "inspect-roster-filter-bar"); JComponent drawer = bar.drawerContent();
            assertNull("The Filters toggle replaces Display filters (N)", find(roster, JToggleButton.class, "inspect-display-filters"));
            JComboBox<?> season = find(roster, JComboBox.class, "inspect-facet-2");
            assertTrue(SwingUtilities.isDescendingFrom(season, drawer));
            assertTrue(SwingUtilities.isDescendingFrom(button(roster, "Sort by guild"), drawer));
            JTextField search = find(roster, JTextField.class, "inspect-roster-search");
            assertTrue(SwingUtilities.isDescendingFrom(search, bar)); assertFalse(SwingUtilities.isDescendingFrom(search, drawer));
            assertFalse(SwingUtilities.isDescendingFrom(button(roster, "Reset display filters"), drawer));
            season.setSelectedIndex(1); find(roster, JComboBox.class, "inspect-facet-4").setSelectedIndex(3);
            assertEquals(Arrays.asList("Seasonal", "Requirements: Below requirements"), ArchiveNativeSupport.chipLabels(bar));
            assertEquals("Filters · 2", find(roster, AbstractButton.class, "inspect-roster-filters").getText());
            ArchiveNativeSupport.removeChip(bar, "Seasonal"); assertEquals(0, season.getSelectedIndex());
            button(roster, "Reset display filters").doClick(); assertEquals(0, bar.activeCount());
        });
    }

    private static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && text.equals(((AbstractButton) child).getText())) return (AbstractButton) child;
            if (child instanceof Container) { AbstractButton found = button((Container) child, text); if (found != null) return found; }
        }
        return null;
    }
    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
