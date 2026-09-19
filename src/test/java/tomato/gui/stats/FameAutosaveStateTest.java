package tomato.gui.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class FameAutosaveStateTest {
    @Test public void staleCompletionsCannotClearNewSamplesOrNewSessionDirtyState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<Consumer<Boolean>> completions = new ArrayList<>();
            List<String> names = new ArrayList<>();
            FameTableBridge.getInstance().setFameTrackerGUI(null);
            new FameTablePanel(null);
            FameTrackerGUI panel = new FameTrackerGUI((session, completion) -> {
                names.add(session.getSessionName()); completions.add(completion);
            });
            FameTrackerGUI.updateFame(1, 100, 1000); panel.triggerAutoSave();
            FameTrackerGUI.updateFame(1, 110, 2000); panel.triggerMapChangeAutoSave();
            completions.get(0).accept(true);
            assertTrue(panel.hasFameGainedSinceLastSave());
            completions.get(1).accept(true);
            assertFalse(panel.hasFameGainedSinceLastSave());

            FameTrackerGUI.updateFame(1, 120, 3000); panel.triggerAutoSave();
            panel.startNewSessionFile();
            assertNotEquals(names.get(2), names.get(3));
            FameTrackerGUI.updateFame(2, 500, 4000);
            completions.get(2).accept(true); // Prior session completes after reset.
            assertTrue(panel.hasFameGainedSinceLastSave());
            completions.get(3).accept(true); // Empty new-session snapshot is already stale.
            assertTrue(panel.hasFameGainedSinceLastSave());
            panel.triggerAutoSave(); completions.get(4).accept(true);
            assertFalse(panel.hasFameGainedSinceLastSave());
            assertFalse(panel.getFameData().containsKey(1));
            assertEquals(1, panel.getFameData().get(2).size());
        });
    }

    @Test public void failedSaveStaysDirtyAndReportsRetryStatusInBothFameViews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<Consumer<Boolean>> completions = new ArrayList<>();
            FameTablePanel table = new FameTablePanel(null);
            FameTrackerGUI panel = new FameTrackerGUI((session, completion) -> completions.add(completion));
            FameTrackerGUI.updateFame(1, 100, 1000); panel.triggerAutoSave();
            completions.get(0).accept(false);
            assertTrue(panel.hasFameGainedSinceLastSave());
            assertTrue(StatisticsExplorerTest.named(panel, "fame-save-status", JLabel.class).getText().contains("failed"));
            assertTrue(StatisticsExplorerTest.named(table, "fame-table-save-status", JLabel.class).getText().contains("failed"));
            panel.triggerAutoSave(); completions.get(1).accept(true);
            assertFalse(panel.hasFameGainedSinceLastSave());
        });
    }
}
