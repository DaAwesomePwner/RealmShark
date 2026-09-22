package tomato;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.modern.WorkspaceShell;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import packets.packetcapture.logger.ActivityJournal;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class SetupWorkspaceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void fullWorkspaceWithoutAssetsCanOpenActualSavedRunsWithoutCreatingAWindowOrCapture() throws Exception {
        Field storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true);
        Object previous = storeField.get(null);
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic");
        int windows = Window.getWindows().length;
        try {
            ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "synthetic-run"; visit.map = "Ice Citadel";
            visit.started = 1000; visit.ended = visit.lastSeen = 2000;
            store.put("runs", visit.id, visit); store.flush(); storeField.set(null, store);
            WorkspaceShell[] shell = new WorkspaceShell[1];
            SwingUtilities.invokeAndWait(() -> {
                shell[0] = (WorkspaceShell)new TomatoGUI(new TomatoData()).createWorkspace();
                TomatoGUI.setSetupState("Assets missing · Choose assets or browse saved history", false, false);
                assertFalse(named(shell[0], "capture-toggle", JButton.class).isEnabled());
                assertTrue(named(shell[0], "choose-assets", JButton.class).isEnabled());
                named(shell[0], "browse-history", JButton.class).doClick();
                assertEquals(10, shell[0].getSelectedPage());
            });
            await(() -> savedActivity(shell[0]) != null);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(named(savedActivity(shell[0]), "activity-summary", JLabel.class).getText().contains("Saved history"));
                TomatoGUI.setSetupState("Assets ready", true, false);
                assertTrue(named(shell[0], "capture-toggle", JButton.class).isEnabled());
            });
            assertFalse(Tomato.isCaptureRunning());
            assertEquals(windows, Window.getWindows().length);
        } finally { storeField.set(null, previous); store.close(); }
    }

    private static ActivityPanel savedActivity(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof ActivityPanel) {
                JLabel summary = named((Container)c, "activity-summary", JLabel.class);
                if (summary.getText().contains("Saved history")) return (ActivityPanel)c;
            }
            if (c instanceof Container) { ActivityPanel found = savedActivity((Container)c); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; }
        }
        return null;
    }
}
