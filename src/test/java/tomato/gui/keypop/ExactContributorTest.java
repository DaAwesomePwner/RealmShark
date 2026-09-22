package tomato.gui.keypop;

import java.awt.event.*;
import java.time.Instant;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExactContributorTest {
    @Test public void sortedKeyboardAndMouseDrilldownsKeepAnExactIndependentPredicate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyPopHistory history = new KeyPopHistory(); Instant now = Instant.now();
            for (String name : new String[]{"Ann", "Anna", "ANN"}) history.add(new KeyPopEvent(now, name, "Halls", KeyPopEvent.Kind.KEY));
            KeyPopDashboard ui = new KeyPopDashboard(history, true);
            ui.search.setText("an halls"); assertEquals(3, ui.events.getRowCount());
            ui.players.getRowSorter().toggleSortOrder(0);
            int ann = -1; for (int row = 0; row < ui.players.getRowCount(); row++) if ("Ann".equalsIgnoreCase((String)ui.players.getValueAt(row, 0))) ann = row;
            ui.players.setRowSelectionInterval(ann, ann);
            ui.players.getActionMap().get("show-events").actionPerformed(new ActionEvent(ui.players, 0, "Enter"));
            assertEquals(2, ui.events.getRowCount()); assertEquals("an halls", ui.search.getText());
            assertEquals("2", ui.metrics[0].getText()); assertEquals(1, ui.players.getRowCount());
            assertTrue(ui.playerChip.getText().startsWith("Player equals Ann"));
            assertEquals(2, ui.filteredEvents().size()); for (KeyPopEvent event : ui.filteredEvents()) assertFalse(event.csvLine().contains("Anna"));
            ui.type.setSelectedItem("Vial"); assertEquals(0, ui.events.getRowCount());
            ui.type.setSelectedItem("Key"); ui.playerChip.doClick(); assertEquals(3, ui.events.getRowCount());
            int anna = -1; for (int row = 0; row < ui.players.getRowCount(); row++) if ("Anna".equals(ui.players.getValueAt(row, 0))) anna = row;
            java.awt.Rectangle cell = ui.players.getCellRect(anna, 0, true);
            MouseEvent click = new MouseEvent(ui.players, MouseEvent.MOUSE_CLICKED, 0, 0, cell.x + 2, cell.y + 2, 2, false);
            for (MouseListener listener : ui.players.getMouseListeners()) listener.mouseClicked(click);
            assertEquals(1, ui.events.getRowCount()); assertEquals("Anna", ui.events.getValueAt(0, 1));
            ui.resetFilters(); assertEquals(3, ui.events.getRowCount()); assertFalse(ui.playerChip.isVisible());
        });
    }
}
