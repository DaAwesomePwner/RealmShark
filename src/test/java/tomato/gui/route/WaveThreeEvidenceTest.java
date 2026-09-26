package tomato.gui.route;

import java.util.ArrayList;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import tomato.gui.modern.WorkspaceShell;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.WaveThreeEvidence.*;

/** Wave 3 visual evidence: the shell's "Back to <page>" action in wide and compact layouts. Synthetic pages only. */
public class WaveThreeEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);

    @After public void restore() throws Exception { run(() -> Navigator.install(Navigator.NONE)); }

    @Test public void shellBackButtonWideAndCompact() throws Exception {
        WorkspaceShell shell = edt(() -> {
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) {
                JPanel page = new JPanel(); page.add(new JLabel("Synthetic " + WorkspaceShell.TITLES[i] + " page")); pages[i] = page;
            }
            WorkspaceShell created = new WorkspaceShell(pages, () -> fail("Synthetic shell must not capture"), true);
            ShellNavigator navigator = created.createNavigator();
            Navigator.install(navigator);
            navigator.register(new ShellNavigatorTest.Fake(Destination.RUNS, new ArrayList<>()));
            created.select(4);
            assertTrue(navigator.open(Route.to(Destination.RUNS)));
            return created;
        });
        for (boolean compact : new boolean[]{false, true}) frame(evidence, shell, "shell-back-to-statistics", compact, () -> {
            // The shell follows its realized width: at 200% this workstation's screen caps a "wide" frame below 1000 logical px.
            assertEquals(shell.getWidth() < 1000, shell.isCompact());
            if (compact) assertTrue(shell.isCompact());
            assertEquals(10, shell.getSelectedPage());
            JButton back = named(shell, "navigate-back", JButton.class);
            assertTrue(back.isShowing() && back.isEnabled());
            assertEquals("Back to Statistics", back.getText());
            VisualEvidence.completeButton(back);
        });
        run(() -> {
            shell.getActionMap().get("navigate-back").actionPerformed(null);
            assertEquals(4, shell.getSelectedPage());
            assertFalse(named(shell, "navigate-back", JButton.class).isVisible());
        });
        frame(evidence, shell, "shell-back-hidden-after-return", false, () -> assertEquals(4, shell.getSelectedPage()));
    }
}
