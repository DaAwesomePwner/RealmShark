package ui;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import tomato.gui.modern.WorkspaceShell;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;

public class SetupLayoutEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence();
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void recoverableErrorRetainsReadableReasonAndUsableRecoveryAndHistoryActions() throws Exception {
        AtomicInteger choose = new AtomicInteger(), retry = new AtomicInteger(), browse = new AtomicInteger();
        WorkspaceShell[] shell = new WorkspaceShell[1];
        String failure = "Assets unavailable: synthetic resources.assets could not be read.\n"
            + "Choose another file or Retry assets. Saved history remains available. No capture has started.";
        SwingUtilities.invokeAndWait(() -> {
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
            pages[10].add(new JLabel("Synthetic saved-run history remains available"));
            shell[0] = new WorkspaceShell(pages, () -> fail("Error state must not start capture"), false,
                choose::incrementAndGet, retry::incrementAndGet, () -> { browse.incrementAndGet(); shell[0].select(10); });
            shell[0].setSetupState(failure, false, false);
        });
        for (int font : new int[]{13, 24}) for (int width : new int[]{1240, 680}) {
            SwingUtilities.invokeAndWait(() -> evidence.show(shell[0], "Recoverable asset error", width, width == 680 ? 520 : 800, font));
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                String name = "setup-error-" + width + "-" + font; evidence.capture(name);
                assertFalse(named(shell[0], "capture-toggle", JButton.class).isEnabled());
                assertEquals(failure, named(shell[0], "capture-setup-message", JTextArea.class).getText());
                layouts.checkSucceeds(() -> {
                    completeText(named(shell[0], "capture-setup-message", JTextArea.class));
                    for (String action : new String[]{"choose-assets", "retry-assets", "browse-history"}) {
                        JButton button = named(shell[0], action, JButton.class);
                        assertTrue(button.isEnabled()); completeButton(button);
                    }
                    return null;
                });
                for (String action : new String[]{"choose-assets", "retry-assets", "browse-history"})
                    named(shell[0], action, JButton.class).doClick();
                evidence.capture(name + "-actions");
            });
        }
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(4, choose.get()); assertEquals(4, retry.get()); assertEquals(4, browse.get());
            assertEquals(10, shell[0].getSelectedPage());
            shell[0].setSetupState("Reading synthetic replacement...", false, true);
            assertFalse(named(shell[0], "retry-assets", JButton.class).isEnabled());
            assertTrue(named(shell[0], "browse-history", JButton.class).isEnabled());
            shell[0].setSetupState(failure, false, false);
            assertTrue(named(shell[0], "retry-assets", JButton.class).isEnabled());
        });
    }
}
