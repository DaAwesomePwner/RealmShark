package tomato.gui.route;

import org.junit.Test;
import tomato.gui.modern.WorkspaceShell;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** The shell exposes Back as a visible, named, keyboard-reachable action bound to its navigator. */
public class ShellBackActionTest {
    @Test public void backActionAppearsOnlyWithAnOriginAndReturnsToIt() throws Exception {
        ShellNavigatorTest.edt(() -> {
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) pages[i] = new JPanel();
            WorkspaceShell shell = new WorkspaceShell(pages, () -> {}, true);
            ShellNavigator navigator = shell.createNavigator();
            ShellNavigatorTest.Fake runs = new ShellNavigatorTest.Fake(Destination.RUNS, new ArrayList<>());
            navigator.register(runs);
            JButton back = named(shell, "navigate-back", JButton.class);
            assertNotNull(back);
            assertFalse("Nothing to return to yet", back.isVisible());
            assertEquals("page-" + 0, shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)));
            assertEquals("navigate-back", shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK)));

            shell.select(4); // Statistics has no target: only its page is remembered.
            assertTrue(navigator.open(Route.to(Destination.RUNS)));
            assertEquals(10, shell.getSelectedPage());
            assertTrue(back.isVisible()); assertTrue(back.isEnabled());
            assertEquals("Back to Statistics", back.getText());
            assertEquals("Back to Statistics", back.getAccessibleContext().getAccessibleName());
            assertTrue(back.getToolTipText().contains("Alt+Left"));

            shell.getActionMap().get("navigate-back").actionPerformed(null);
            assertEquals(4, shell.getSelectedPage());
            assertFalse(back.isVisible());
            assertEquals("Unrouted destinations have no shell page", ShellNavigator.NO_PAGE, WorkspaceShell.pageOf(Destination.ALERT_DRAFT));
            for (Destination destination : Destination.values()) {
                int page = WorkspaceShell.pageOf(destination);
                assertTrue(destination + " maps to a real page", page == ShellNavigator.NO_PAGE || (page >= 0 && page < WorkspaceShell.TITLES.length));
            }
            assertEquals("Runs", WorkspaceShell.TITLES[WorkspaceShell.pageOf(Destination.RUNS)]);
            assertEquals("Timeline", WorkspaceShell.TITLES[WorkspaceShell.pageOf(Destination.TIMELINE)]);
            assertEquals("Loot", WorkspaceShell.TITLES[WorkspaceShell.pageOf(Destination.LOOT)]);
            assertEquals("Inspect", WorkspaceShell.TITLES[WorkspaceShell.pageOf(Destination.INSPECT)]);
            return null;
        });
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
