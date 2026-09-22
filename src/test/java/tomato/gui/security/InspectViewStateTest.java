package tomato.gui.security;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.*;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;

public class InspectViewStateTest {
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
