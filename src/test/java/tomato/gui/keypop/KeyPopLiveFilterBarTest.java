package tomato.gui.keypop;

import java.awt.*;
import java.time.Instant;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class KeyPopLiveFilterBarTest {
    @Test public void keyPopFiltersLiveInTheDrawerWithChips() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyPopHistory history = new KeyPopHistory(); Instant now = Instant.now();
            history.add(new KeyPopEvent(now, "Ann", "Halls", KeyPopEvent.Kind.KEY)); history.add(new KeyPopEvent(now.plusSeconds(1), "Bo", "Shatters", KeyPopEvent.Kind.VIAL));
            KeyPopDashboard ui = new KeyPopDashboard(history);
            FilterBar bar = find(ui, FilterBar.class, "keypops-live-filter-bar"); JComponent drawer = bar.drawerContent();
            assertTrue(SwingUtilities.isDescendingFrom(ui.type, drawer)); assertTrue(SwingUtilities.isDescendingFrom(ui.playerChip, drawer));
            assertTrue(SwingUtilities.isDescendingFrom(ui.search, bar)); assertFalse(SwingUtilities.isDescendingFrom(ui.search, drawer));
            ui.type.setSelectedItem("Key"); ui.selectPlayer("Ann");
            assertEquals(Arrays.asList("Player: Ann", "Type: Key"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Type: Key");
            assertEquals(0, ui.type.getSelectedIndex()); assertTrue(ui.playerChip.isVisible());
            find(ui, AbstractButton.class, "keypops-live-clear-filters").doClick();
            assertFalse(ui.playerChip.isVisible()); assertEquals(0, bar.activeCount());
        });
    }

    /** P6b: the row is the dashboard's LiveFilterHost bar, and multi-select and dates are plain drawer sections without a toggle. */
    @Test public void theRowIsTheHostBarAndTheDrawerShowsMultiSelectAndDatesWithoutAToggle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyPopDashboard ui = new KeyPopDashboard(new KeyPopHistory());
            FilterBar bar = find(ui, FilterBar.class, "keypops-live-filter-bar");
            assertSame("The dashboard lends this row to its workspace's Scope chip", bar, ui.liveFilterBar());
            JComponent drawer = bar.drawerContent();
            for (String name : new String[]{"keypop-live-kinds", "keypop-live-items", "social-date-from", "social-date-until"}) {
                Component part = find(drawer, Component.class, name); assertNotNull(name, part);
                for (Component c = part; c != drawer; c = c.getParent()) assertTrue(name + " shows without a toggle", c.isVisible());
            }
            for (AbstractButton button : buttons(ui)) assertNotEquals("Multi-select / absolute dates / view state", button.getText());
        });
    }

    private static java.util.List<AbstractButton> buttons(Container root) {
        java.util.List<AbstractButton> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton) result.add((AbstractButton) child);
            if (child instanceof Container) result.addAll(buttons((Container) child));
        }
        return result;
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
