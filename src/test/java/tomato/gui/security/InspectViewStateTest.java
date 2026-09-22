package tomato.gui.security;

import java.util.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.*;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;

public class InspectViewStateTest {
    @Test public void historicalSaveButtonCannotReplaceRememberedLiveFilters() throws Exception {
        Memory memory = new Memory(); RosterDefinitions definitions = RequirementResultTest.definitions();
        SwingUtilities.invokeAndWait(() -> {
            ParsePanelGUI view = new ParsePanelGUI(true, () -> definitions); view.bindViewState(memory.store);
            JTextField search = named(view, "inspect-roster-search", JTextField.class);
            JButton save = named(view, "inspect-live-roster-save-state", JButton.class);
            JButton reset = named(view, "inspect-live-roster-reset-state", JButton.class);
            search.setText("Live L"); assertTrue(visibleIn(view, save)); save.doClick(0);
            view.showRun("recorded-run", Collections.singleton(new InspectSnapshot(RequirementResultTest.player(7).playerEntity, 1234)));
            String liveDocument = memory.values.get("ux.archive.inspect-live-roster"); int writes = memory.writes;
            search.setText("Historical H");
            save.doClick(0); // Exercise the actual control action, not the guarded panel API.
            assertEquals(liveDocument, memory.values.get("ux.archive.inspect-live-roster"));
            assertEquals(writes, memory.writes);
            assertFalse(visibleIn(view, save)); assertFalse(save.isEnabled()); assertFalse(reset.isEnabled());
            // A previously queued action must still be rejected even if it bypasses disabled-button dispatch.
            invokeAction(save); reset.doClick(0); invokeAction(reset);
            assertEquals(liveDocument, memory.values.get("ux.archive.inspect-live-roster")); assertEquals(writes, memory.writes);
            view.showCurrentArea(); assertEquals("Live L", search.getText()); assertTrue(visibleIn(view, save)); assertTrue(save.isEnabled());
        });
    }
    @Test public void queuedSaveAndResetFromLiveAreInertAfterRecordedBoundary() throws Exception {
        Memory memory = new Memory(); RosterDefinitions definitions = RequirementResultTest.definitions();
        ParsePanelGUI[] view = new ParsePanelGUI[1]; String[] liveDocument = new String[1]; int[] writes = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new ParsePanelGUI(true, () -> definitions); view[0].bindViewState(memory.store);
            JTextField search = named(view[0], "inspect-roster-search", JTextField.class);
            search.setText("Queued live L"); // Coalesced automatic save is still queued on the EDT.
            JButton save = named(view[0], "inspect-live-roster-save-state", JButton.class);
            JButton reset = named(view[0], "inspect-live-roster-reset-state", JButton.class);
            SwingUtilities.invokeLater(() -> invokeAction(save)); SwingUtilities.invokeLater(() -> invokeAction(reset));
            view[0].showRun("recorded-run", Collections.emptyList());
            liveDocument[0] = memory.values.get("ux.archive.inspect-live-roster"); writes[0] = memory.writes;
            search.setText("Queued historical H");
        });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(liveDocument[0], memory.values.get("ux.archive.inspect-live-roster")); assertEquals(writes[0], memory.writes);
            view[0].showCurrentArea(); assertEquals("Queued live L", named(view[0], "inspect-roster-search", JTextField.class).getText());
            // Reset is available again only after live ownership resumes, and invalidates earlier queued saves.
            named(view[0], "inspect-live-roster-reset-state", JButton.class).doClick(0);
        });
        SwingUtilities.invokeAndWait(() -> assertEquals("", memory.values.get("ux.archive.inspect-live-roster")));
    }
    private static void invokeAction(JButton button) {
        for (java.awt.event.ActionListener listener : button.getActionListeners()) listener.actionPerformed(new ActionEvent(button, ActionEvent.ACTION_PERFORMED, "queued-live-action"));
    }
    private static boolean visibleIn(Container owner, Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (!current.isVisible()) return false;
            if (current == owner) return true;
        }
        return false;
    }
    @Test public void liveUnknownFacetsPersistWithoutHistoricalViewOrObjectSelectionLeakingIntoThem() throws Exception {
        Memory memory = new Memory(); RosterDefinitions definitions = RequirementResultTest.definitions();
        SwingUtilities.invokeAndWait(() -> {
            ParsePanelGUI live = new ParsePanelGUI(true, () -> definitions); live.bindViewState(memory.store);
            Player player = RequirementResultTest.player(7); ParsePanelGUI.addPlayer(7, player.playerEntity); live.refreshRoster();
            named(live, "inspect-roster-search", JTextField.class).setText("Future literal [abc]");
            named(live, "inspect-facet-1", JComboBox.class).setSelectedIndex(2);
            named(live, "inspect-facet-2", JComboBox.class).setSelectedIndex(3);
            named(live, "inspect-facet-3", JComboBox.class).setSelectedIndex(3);
            named(live, "inspect-facet-4", JComboBox.class).setSelectedIndex(4);
            named(live, "inspect-facet-5", JComboBox.class).setSelectedIndex(2);
            named(live, "inspect-facet-6", JSpinner.class).setValue(3);
            live.refreshRoster(); assertEquals(1, live.getFilteredPlayers().size()); live.saveViewState();
            live.showRun("saved-run", Collections.singleton(new InspectSnapshot(player.playerEntity, 1234)));
            named(live, "inspect-roster-search", JTextField.class).setText("Historical only");
            live.showCurrentArea(); assertEquals("Future literal [abc]", named(live, "inspect-roster-search", JTextField.class).getText());
            ParsePanelGUI reopened = new ParsePanelGUI(true, () -> definitions); reopened.bindViewState(memory.store);
            assertEquals("Future literal [abc]", named(reopened, "inspect-roster-search", JTextField.class).getText());
            for (int facet : new int[]{2, 3}) assertEquals(3, named(reopened, "inspect-facet-" + facet, JComboBox.class).getSelectedIndex());
            assertEquals(4, named(reopened, "inspect-facet-4", JComboBox.class).getSelectedIndex());
            assertEquals(3, named(reopened, "inspect-facet-6", JSpinner.class).getValue());
            assertFalse(memory.values.toString().contains("object:7")); assertFalse(memory.values.toString().contains("Historical only"));
            ParsePanelGUI.clear();
        });
    }
}
