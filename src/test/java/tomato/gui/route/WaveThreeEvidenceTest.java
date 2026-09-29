package tomato.gui.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import tomato.gui.modern.NavEntry;
import tomato.gui.modern.TestPages;
import tomato.gui.modern.WorkspaceShell;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.WaveThreeEvidence.*;

/** Wave 3 visual evidence: the shell's "Back to <page>" action in wide and compact layouts. Synthetic pages only. */
public class WaveThreeEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);
    @Rule public FixtureZone zone = new FixtureZone();

    @After public void restore() throws Exception { run(() -> Navigator.install(Navigator.NONE)); }

    @Test public void shellBackButtonWideAndCompact() throws Exception {
        WorkspaceShell shell = edt(() -> {
            Map<String, JComponent> pages = new LinkedHashMap<>();
            for (NavEntry entry : NavEntry.defaults()) {
                JPanel page = new JPanel(); page.add(new JLabel("Synthetic " + entry.title() + " page")); pages.put(entry.id(), page);
            }
            WorkspaceShell created = new WorkspaceShell(pages, () -> fail("Synthetic shell must not capture"), true);
            ShellNavigator navigator = created.createNavigator();
            Navigator.install(navigator);
            navigator.register(new ShellNavigatorTest.Fake(Destination.RUNS, new ArrayList<>()));
            created.select("statistics");
            assertTrue(navigator.open(Route.to(Destination.RUNS)));
            return created;
        });
        for (boolean compact : new boolean[]{false, true}) frame(evidence, shell, "shell-back-to-statistics", compact, () -> {
            // The shell follows its realized width: at 200% this workstation's screen caps a "wide" frame below 1000 logical px.
            assertEquals(shell.getWidth() < 1000, shell.isCompact());
            if (compact) assertTrue(shell.isCompact());
            assertEquals("runs", shell.selectedPage());
            JButton back = named(shell, "navigate-back", JButton.class);
            assertTrue(back.isShowing() && back.isEnabled());
            assertEquals("Back to Statistics", back.getText());
            VisualEvidence.completeButton(back);
        });
        run(() -> {
            shell.getActionMap().get("navigate-back").actionPerformed(null);
            assertEquals("statistics", shell.selectedPage());
            assertFalse(named(shell, "navigate-back", JButton.class).isVisible());
        });
        frame(evidence, shell, "shell-back-hidden-after-return", false, () -> assertEquals("statistics", shell.selectedPage()));
    }

    /** A route that stays on the current page (Runs to a routed Runs view) must not label Back with the page already shown. */
    @Test public void shellBackWithinTheSamePage() throws Exception {
        ShellNavigator[] navigator = new ShellNavigator[1];
        ArrayList<String> log = new ArrayList<>();
        WorkspaceShell shell = edt(() -> {
            Map<String, JComponent> pages = new LinkedHashMap<>();
            for (NavEntry entry : NavEntry.defaults()) {
                JPanel page = new JPanel(); page.add(new JLabel("Synthetic " + entry.title() + " page")); pages.put(entry.id(), page);
            }
            WorkspaceShell created = new WorkspaceShell(pages, () -> fail("Synthetic shell must not capture"), true);
            navigator[0] = created.createNavigator(); Navigator.install(navigator[0]);
            navigator[0].register(new ShellNavigatorTest.Fake(Destination.RUNS, log));
            created.select("runs");
            assertTrue(navigator[0].open(Route.to(Destination.RUNS)));
            return created;
        });
        long token = edt(navigator[0]::backToken);
        for (boolean compact : new boolean[]{false, true}) frame(evidence, shell, "shell-back-same-page", compact, () -> {
            assertEquals("runs", shell.selectedPage());
            JButton back = named(shell, "navigate-back", JButton.class);
            assertTrue(back.isShowing());
            assertNotEquals("Back does not name the current page", "Back to " + TestPages.title(shell.selectedPage()), back.getText());
            assertEquals(WorkspaceShell.BACK_TO_PREVIOUS_VIEW, back.getText());
            assertEquals(WorkspaceShell.BACK_TO_PREVIOUS_VIEW, back.getAccessibleContext().getAccessibleName());
            VisualEvidence.completeButton(back);
        });
        run(() -> {
            // Leaving the page by the sidebar names the routed origin again; the stack and token are unchanged.
            shell.select("statistics");
            assertEquals("Back to Runs & DPS", named(shell, "navigate-back", JButton.class).getText());
            assertEquals(token, navigator[0].backToken());
            shell.select("runs");
            assertEquals(WorkspaceShell.BACK_TO_PREVIOUS_VIEW, named(shell, "navigate-back", JButton.class).getText());
            shell.getActionMap().get("navigate-back").actionPerformed(null);
            assertEquals("Back behaviour is unchanged: it returns to the routed origin", "runs", shell.selectedPage());
            assertFalse(navigator[0].canGoBack());
            assertFalse(named(shell, "navigate-back", JButton.class).isVisible());
        });
    }
}
