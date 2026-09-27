package tomato.gui.security;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class InspectRosterFilterBarTest {
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
