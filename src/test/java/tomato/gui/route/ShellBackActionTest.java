package tomato.gui.route;

import org.junit.Test;
import tomato.gui.modern.NavEntry;
import tomato.gui.modern.TestPages;
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
            WorkspaceShell shell = new WorkspaceShell(TestPages.placeholders(), () -> {}, true);
            ShellNavigator navigator = shell.createNavigator();
            ShellNavigatorTest.Fake runs = new ShellNavigatorTest.Fake(Destination.RUNS, new ArrayList<>());
            navigator.register(runs);
            JButton back = named(shell, "navigate-back", JButton.class);
            assertNotNull(back);
            assertFalse("Nothing to return to yet", back.isVisible());
            assertEquals("page-chat", shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)));
            assertEquals("page-key-pops", shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.ALT_DOWN_MASK)));
            assertEquals("chat", WorkspaceShell.pageOf(Destination.CHAT));
            assertEquals("key-pops", WorkspaceShell.pageOf(Destination.KEYPOPS));
            assertEquals("settings", WorkspaceShell.pageOf(Destination.SETTINGS));
            assertEquals("navigate-back", shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK)));

            shell.select("quests"); // No Quests target here: only its page is remembered (Statistics, the old fixture, is gone).
            assertTrue(navigator.open(Route.to(Destination.RUNS)));
            assertEquals("runs", shell.selectedPage());
            assertTrue(back.isVisible()); assertTrue(back.isEnabled());
            assertEquals("Back to Quests", back.getText());
            assertEquals("Back to Quests", back.getAccessibleContext().getAccessibleName());
            assertTrue(back.getToolTipText().contains("Alt+Left"));

            shell.getActionMap().get("navigate-back").actionPerformed(null);
            assertEquals("quests", shell.selectedPage());
            assertFalse(back.isVisible());
            assertEquals("Unrouted destinations have no shell page", ShellNavigator.NO_PAGE, WorkspaceShell.pageOf(Destination.ALERT_DRAFT));
            for (Destination destination : Destination.values()) {
                String page = WorkspaceShell.pageOf(destination);
                assertTrue(destination + " maps to a real page", page == ShellNavigator.NO_PAGE || NavEntry.forId(page) != null);
            }
            assertEquals("Runs & DPS", TestPages.title(WorkspaceShell.pageOf(Destination.RUNS)));
            assertEquals("Timeline", TestPages.title(WorkspaceShell.pageOf(Destination.TIMELINE)));
            assertEquals("Loot", TestPages.title(WorkspaceShell.pageOf(Destination.LOOT)));
            assertEquals("Party", TestPages.title(WorkspaceShell.pageOf(Destination.INSPECT)));
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
