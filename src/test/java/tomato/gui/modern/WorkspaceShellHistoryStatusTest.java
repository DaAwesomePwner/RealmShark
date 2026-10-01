package tomato.gui.modern;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.Tokens;
import tomato.history.SessionStore;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;

public class WorkspaceShellHistoryStatusTest {
    @Test public void skippedRecordsAreNeverLabelledAsRetryingAndCountsExtendSanitizedFailureDetails() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> preferences = new HashMap<>();
            AtomicReference<String> error = new AtomicReference<>("17" + SessionStore.RECORDS_SKIPPED);
            WorkspaceShell shell = new WorkspaceShell(TestPages.placeholders(), () -> {}, true, null, null, null,
                new NavLayout(preferences::get, preferences::put),
                new DisplayModeModel(preferences::get, preferences::put), error::get);
            JLabel status = named(shell, "history-status", JLabel.class);
            shell.refreshHistoryStatus();
            assertTrue(status.isVisible());
            assertEquals("Some history records were skipped", status.getText());
            assertEquals("17 history records could not be saved and were skipped.", status.getToolTipText());
            String[][] cases = {
                {SessionStore.SAVE_FAILED + "C:\\Users\\SyntheticPrivate\\history", "History not saved, retrying",
                    "History could not be saved; pending data will retry. Check the history folder."},
                {SessionStore.SNAPSHOT_FAILED + "IllegalStateException: SyntheticPrivate", "History snapshot could not be collected",
                    "A history snapshot could not be collected. Collection will retry."},
                {SessionStore.UNSAVED_ON_CLOSE + "/home/synthetic-private/history", "History has unsaved data",
                    "History has unsaved data; check the history folder."},
                {SessionStore.IMPORT_FAILED, "Some history could not be imported", SessionStore.IMPORT_FAILED}
            };
            for (String[] failure : cases) {
                error.set(failure[0] + " · 23" + SessionStore.RECORDS_SKIPPED); shell.refreshHistoryStatus();
                assertEquals(failure[1], status.getText());
                assertEquals(failure[2] + " 23 history records could not be saved and were skipped.", status.getToolTipText());
            }
            error.set(""); shell.refreshHistoryStatus(); assertFalse(status.isVisible()); assertNull(status.getToolTipText());
        });
    }
    @Test public void historyFailuresAppearWithoutStoragePathsAndClearAfterRecovery() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> preferences = new HashMap<>();
            AtomicReference<String> error = new AtomicReference<>("");
            WorkspaceShell shell = new WorkspaceShell(TestPages.placeholders(), () -> {}, true, null, null, null,
                new NavLayout(preferences::get, preferences::put),
                new DisplayModeModel(preferences::get, preferences::put), error::get);
            JLabel status = named(shell, "history-status", JLabel.class);
            assertFalse(status.isVisible());
            error.set(SessionStore.SAVE_FAILED + "C:\\Users\\SyntheticPrivate\\history");
            shell.refreshHistoryStatus();
            assertTrue(status.isVisible());
            assertEquals("History not saved, retrying", status.getText());
            assertEquals(Tokens.color(Tokens.Role.BAD), status.getForeground());
            assertFalse(status.getToolTipText().contains("SyntheticPrivate"));
            assertFalse(status.getToolTipText().contains("C:"));
            assertTrue(status.getToolTipText().contains("history folder"));
            error.set(SessionStore.IMPORT_FAILED);
            shell.refreshHistoryStatus();
            assertEquals("Some history could not be imported", status.getText());
            error.set(SessionStore.SNAPSHOT_FAILED + "IllegalStateException");
            shell.refreshHistoryStatus();
            assertEquals("History snapshot could not be collected", status.getText());
            error.set(SessionStore.UNSAVED_ON_CLOSE + "/home/synthetic-private/history");
            shell.refreshHistoryStatus();
            assertEquals("History has unsaved data", status.getText());
            assertFalse(status.getToolTipText().contains("/home/"));
            error.set("");
            shell.refreshHistoryStatus();
            assertFalse(status.isVisible());
            assertNull(status.getToolTipText());
            error.set(null);
            shell.refreshHistoryStatus();
            assertFalse(status.isVisible());
        });
    }
}
