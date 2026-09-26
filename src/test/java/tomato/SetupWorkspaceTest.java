package tomato;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.history.ArchiveWorkspace;
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

    @Test public void previewWorkspaceWithoutAssetsCanOpenQueriedSavedRunsWithoutCreatingAWindowOrCapture() throws Exception {
        Field storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true);
        Object previous = storeField.get(null);
        Field previewField = Tomato.class.getDeclaredField("preview"); previewField.setAccessible(true);
        Object previousPreview = previewField.get(null); previewField.set(null, true);
        String runsState = util.PropertiesManager.getProperty("ux.archive.runs");
        util.PropertiesManager.setProperties("ux.archive.runs", "");
        String temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic");
        TomatoGUI gui = new TomatoGUI(new TomatoData());
        WorkspaceShell[] shell = new WorkspaceShell[1];
        // Window.getWindows() also lists disposed windows until they are garbage collected, so compare
        // identities: a count can drop mid-test when an earlier test's window is collected.
        java.util.Set<Window> windows = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        windows.addAll(java.util.Arrays.asList(Window.getWindows()));
        try {
            ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "synthetic-run"; visit.map = "Ice Citadel";
            visit.started = 1000; visit.ended = visit.lastSeen = 2000;
            store.put("runs", visit.id, visit); store.flush(); storeField.set(null, store);
            SwingUtilities.invokeAndWait(() -> {
                shell[0] = (WorkspaceShell)gui.createWorkspace();
                TomatoGUI.setSetupState("Assets missing · Choose assets or browse saved history", false, false);
                assertFalse(named(shell[0], "capture-toggle", JButton.class).isEnabled());
                assertFalse(named(shell[0], "choose-assets", JButton.class).isEnabled());
                named(shell[0], "browse-history", JButton.class).doClick();
                assertEquals(10, shell[0].getSelectedPage());
            });
            ArchiveWorkspace<?,?,?> runs = named(shell[0], "runs-session-view", ArchiveWorkspace.class);
            assertNotNull(runs);
            await(() -> !runs.loading() && runs.displayedPage() != null);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(SessionStore.ALL, runs.state().query.scope());
                assertEquals(1, runs.displayedPage().matches);
                assertEquals("Ice Citadel", named(runs, "saved-activity-table", JTable.class).getValueAt(0, 0));
                TomatoGUI.setSetupState("Assets ready", true, false);
                assertFalse(named(shell[0], "capture-toggle", JButton.class).isEnabled());
            });
            assertFalse(Tomato.isCaptureRunning());
            for (Window window : Window.getWindows()) assertTrue("Browsing history must not open a window", windows.contains(window));
        } finally {
            gui.closeWorkspace();
            SwingUtilities.invokeAndWait(() -> { if (shell[0] != null) shell[0].removeNotify(); });
            util.PropertiesManager.setProperties("ux.archive.runs", runsState == null ? "" : runsState);
            System.setProperty("java.io.tmpdir", temporaryDirectory);
            storeField.set(null, previous); previewField.set(null, previousPreview); store.close();
        }
    }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; }
        }
        return null;
    }
}
