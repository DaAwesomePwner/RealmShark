package tomato.gui.modern;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.route.Destination;
import static org.junit.Assert.*;
import static tomato.gui.modern.TestPages.listedRows;
import static tomato.gui.modern.TestPages.menuPages;

/** Sidebar grouping, customization, compact menu and landing over in-memory preferences. */
public class WorkspaceShellLayoutTest {
    @Test public void contextMenuReordersHidesAndRestoresCoreDestinations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            click(shell.contextMenu("characters"), "nav-menu-move-up");
            assertEquals(Arrays.asList("characters", "home", "runs", "loot", "quests", "chat"), listedRows(shell));
            assertTrue(store.get(NavLayout.ORDER_KEY).startsWith("characters,home,"));
            assertFalse("The first row cannot move up", item(shell.contextMenu("characters"), "nav-menu-move-up").isEnabled());
            click(shell.contextMenu("loot"), "nav-menu-hide");
            assertFalse(named(shell, "nav-loot", AbstractButton.class).isVisible());
            assertEquals("loot", store.get(NavLayout.HIDDEN_KEY));
            assertEquals(Arrays.asList("characters", "home", "runs", "quests", "chat"), listedRows(shell));
            JMenu hidden = (JMenu) item(shell.contextMenu("characters"), "nav-menu-show-hidden");
            assertTrue(hidden.isEnabled());
            assertEquals("Loot", hidden.getItem(0).getText());
            click(shell.contextMenu("characters"), "nav-menu-show-loot");
            assertTrue(named(shell, "nav-loot", AbstractButton.class).isVisible());
            click(shell.contextMenu("home"), "nav-menu-reset");
            assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat"), listedRows(shell));
            assertEquals("", store.get(NavLayout.ORDER_KEY));
            assertEquals("", store.get(NavLayout.HIDDEN_KEY));
        });
    }

    @Test public void advancedDestinationsPinToTheCoreListAndUnpinBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            assertNull("Core rows have nothing to pin", item(shell.contextMenu("home"), "nav-menu-pin"));
            click(shell.contextMenu("timeline"), "nav-menu-pin");
            assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat", "timeline"), listedRows(shell));
            assertEquals("Advanced (4)", named(shell, "nav-advanced", AbstractButton.class).getText());
            assertEquals("timeline", store.get(NavLayout.PINNED_KEY));
            click(shell.contextMenu("timeline"), "nav-menu-move-up");
            assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "timeline", "chat"), listedRows(shell));
            click(shell.contextMenu("timeline"), "nav-menu-unpin");
            assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat"), listedRows(shell));
            assertEquals("", store.get(NavLayout.PINNED_KEY));
            assertNotNull(item(shell.contextMenu("timeline"), "nav-menu-pin"));
        });
    }

    @Test public void settingsOffersOnlyRestoreAndResetAndTheLastCoreRowStays() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            JPopupMenu settings = shell.contextMenu("settings");
            assertEquals("nav-menu", settings.getName());
            for (String absent : new String[] {"nav-menu-move-up", "nav-menu-move-down", "nav-menu-hide", "nav-menu-pin"})
                assertNull(absent, item(settings, absent));
            assertFalse("Nothing is hidden yet", item(settings, "nav-menu-show-hidden").isEnabled());
            assertTrue(item(settings, "nav-menu-reset").isEnabled());
            for (String page : new String[] {"home", "characters", "runs", "loot", "quests"}) click(shell.contextMenu(page), "nav-menu-hide");
            assertFalse("The last visible core row cannot be hidden", item(shell.contextMenu("chat"), "nav-menu-hide").isEnabled());
        });
    }

    @Test public void focusedRowsMoveWithCtrlShiftArrowsAndOpenTheirMenuFromTheKeyboard() throws Exception {
        JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkspaceShell shell = shell();
                frame[0] = new JFrame("Navigation keyboard");
                frame[0].setContentPane(shell); frame[0].setSize(1240, 800); frame[0].setVisible(true);
                AbstractButton runs = named(shell, "nav-runs", AbstractButton.class);
                invoke(runs, KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
                assertEquals(Arrays.asList("home", "runs", "characters", "loot", "quests", "chat"), listedRows(shell));
                invoke(runs, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
                assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat"), listedRows(shell));
                assertNotNull(runs.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0)));
                invoke(runs, KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK));
                MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
                assertTrue("The menu opens with a keyboard selection", path.length >= 2);
                assertEquals("nav-menu", path[0].getComponent().getName());
                assertEquals("nav-menu-move-up", path[1].getComponent().getName());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                if (frame[0] != null) frame[0].dispose();
            });
        }
    }
    @Test public void compactMenuFollowsTheSidebarGroupsAndLeavesHiddenPagesOut() throws Exception {
        store.put(NavLayout.HIDDEN_KEY, "loot");
        store.put(NavLayout.PINNED_KEY, "timeline");
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            JPopupMenu popup = named(shell, "compact-navigation", AbstractButton.class).getComponentPopupMenu();
            assertEquals(Arrays.asList("home", "characters", "runs", "quests", "chat", "timeline", "party", "key-pops", "logging", "bridge-review", "settings"), menuPages(popup));
            JLabel group = named(popup, "compact-nav-advanced", JLabel.class);
            assertTrue(group.isVisible());
            assertEquals("Advanced", group.getText());
            int separators = 0;
            for (Component child : popup.getComponents()) if (child instanceof JSeparator && child.isVisible()) separators++;
            assertEquals("Core | Advanced | Settings", 2, separators);
            shell.select("loot");
            assertEquals("The current page is listed even when hidden",
                Arrays.asList("home", "characters", "runs", "loot", "quests", "chat", "timeline", "party", "key-pops", "logging", "bridge-review", "settings"), menuPages(popup));
            assertTrue(named(popup, "compact-nav-loot", JMenuItem.class).isSelected());
            click(shell.contextMenu("chat"), "nav-menu-move-up");
            assertEquals("Reordering reaches the menu",
                Arrays.asList("home", "characters", "runs", "loot", "chat", "quests", "timeline", "party", "key-pops", "logging", "bridge-review", "settings"), menuPages(popup));
        });
    }
    @Test public void landingIsTheFirstVisibleCoreDestinationInTheUsersOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            assertEquals("Shells built directly still open on page 0", "chat", shell.selectedPage());
            shell.selectLanding();
            assertEquals("Home is the default landing page", "home", shell.selectedPage());
            click(shell.contextMenu("home"), "nav-menu-hide");
            shell.selectLanding();
            assertEquals("Hidden rows are skipped", "characters", shell.selectedPage());
            store.put(NavLayout.ORDER_KEY, "quests,home");
            WorkspaceShell reordered = shell();
            reordered.selectLanding();
            assertEquals("A reordered list lands on its new first row", "quests", reordered.selectedPage());
            store.remove(NavLayout.HIDDEN_KEY);
            store.put(NavLayout.ORDER_KEY, "quests");
            WorkspaceShell upgraded = shell();
            upgraded.selectLanding();
            assertEquals("A saved order from before Home gains Home in front", "home", upgraded.selectedPage());
            assertEquals("Reading never rewrites the saved order", "quests", store.get(NavLayout.ORDER_KEY));
        });
    }
    @Test public void hidingARowNeverAnchorsOnAHiddenRowAndFocusFallsToThePage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, JComponent> pages = TestPages.placeholders();
            JButton build = new JButton("Estimate");
            pages.get("my-info").add(build);
            WorkspaceShell shell = new WorkspaceShell(pages, () -> {}, true, null, null, null, new NavLayout(store::get, store::put));
            JFrame frame = new JFrame("Hidden row focus");
            frame.setContentPane(shell); frame.setSize(1240, 800); frame.setVisible(true);   // the focus traversal policy orders only a showing window
            try {
                shell.select("my-info");   // Build: unlisted, no row
                click(shell.contextMenu("loot"), "nav-menu-hide");
                assertFalse(named(shell, "nav-loot", AbstractButton.class).isVisible());
                assertNull("No visible row to anchor on while Build is current", shell.scrollAnchor());
                assertSame("Focus goes into Build, which has no row", build, shell.focusTarget("my-info"));
                click(shell.contextMenu("characters"), "nav-menu-show-loot");
                shell.select("loot");
                click(shell.contextMenu("loot"), "nav-menu-hide");   // the current page's own row
                AbstractButton lootRow = named(shell, "nav-loot", AbstractButton.class);
                assertTrue("The current page's row stays listed while it is current", lootRow.isVisible());
                assertSame("…so it still anchors and takes focus", lootRow, shell.scrollAnchor());
                assertSame(lootRow, shell.focusTarget("loot"));
                shell.select("characters");
                assertFalse("Once another page is current, the hidden row leaves the sidebar", lootRow.isVisible());
                click(shell.contextMenu("runs"), "nav-menu-hide");
                JToggleButton characters = named(shell, "nav-characters", JToggleButton.class);
                assertSame("A visible current row anchors and takes focus", characters, shell.scrollAnchor());
                assertSame(characters, shell.focusTarget("characters"));
            } finally { frame.dispose(); }
        });
    }

    private final Map<String, String> store = new HashMap<>();

    private WorkspaceShell shell() {
        Map<String, JComponent> pages = TestPages.placeholders();
        WorkspaceShell shell = new WorkspaceShell(pages, () -> {}, true, null, null, null, new NavLayout(store::get, store::put));
        resize(shell, 1240, 800);
        return shell;
    }

    @Test public void coreDestinationsComeFirstAndAdvancedStartsCollapsed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat"), listedRows(shell));
            AbstractButton advanced = named(shell, "nav-advanced", AbstractButton.class);
            assertEquals("Advanced (5)", advanced.getText());
            assertEquals("Advanced pages", advanced.getAccessibleContext().getAccessibleName());
            assertEquals("Collapsed", advanced.getAccessibleContext().getAccessibleDescription());
            advanced.doClick();
            assertEquals("true", store.get(NavLayout.ADVANCED_KEY));
            assertEquals("Expanded", advanced.getAccessibleContext().getAccessibleDescription());
            assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat", "party", "key-pops", "timeline", "logging", "bridge-review"), listedRows(shell));
            assertEquals("The open group is remembered", Arrays.asList("home", "characters", "runs", "loot", "quests", "chat", "party", "key-pops", "timeline", "logging", "bridge-review"), listedRows(shell()));
        });
    }

    @Test public void settingsStaysBelowTheScrollingListAndOpensWithAltComma() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            AbstractButton settings = named(shell, "nav-settings", AbstractButton.class);
            assertTrue(settings.isVisible());
            assertNull("Settings is outside the scrolling list", SwingUtilities.getAncestorOfClass(JViewport.class, settings));
            assertEquals("Settings", settings.getText());
            assertEquals("Settings", settings.getAccessibleContext().getAccessibleName());
            assertTrue(settings.getToolTipText(), settings.getToolTipText().contains("Alt+,"));
            Object binding = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.ALT_DOWN_MASK));
            assertEquals("open-settings", binding);
            shell.getActionMap().get(binding).actionPerformed(null);
            assertEquals("settings", shell.selectedPage());
            assertEquals("page-settings", shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.ALT_DOWN_MASK)));
        });
    }

    @Test public void theCurrentPageStaysVisibleWhileCollapsedOrHidden() throws Exception {
        store.put(NavLayout.HIDDEN_KEY, "loot");
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            AbstractButton bridge = named(shell, "nav-bridge-review", AbstractButton.class), loot = named(shell, "nav-loot", AbstractButton.class);
            assertFalse(bridge.isVisible());
            assertFalse(loot.isVisible());
            shell.select("bridge-review");
            assertTrue("A collapsed Advanced page shows while it is current", bridge.isVisible());
            assertTrue(bridge.isSelected());
            assertEquals(Arrays.asList("home", "characters", "runs", "quests", "chat", "bridge-review"), listedRows(shell));
            shell.select("loot");
            assertTrue("A hidden page shows while it is current", loot.isVisible());
            assertFalse(bridge.isVisible());
            shell.select("my-info");
            assertFalse(loot.isVisible());
            assertFalse("Build never shows in the sidebar, even while current", named(shell, "nav-my-info", AbstractButton.class).isVisible());
        });
    }

    @Test public void titlesAndRoutesKeepTheirPageIndices() throws Exception {
        assertEquals(15, NavEntry.defaults().size());
        assertEquals("home", WorkspaceShell.pageOf(Destination.HOME));
        assertEquals("my-info", WorkspaceShell.pageOf(Destination.MY_INFO));
        assertEquals("Home", TestPages.title("home"));
        assertEquals("Build", TestPages.title("my-info"));
        assertEquals("Party", TestPages.title(WorkspaceShell.pageOf(Destination.INSPECT)));
        assertEquals("Quests", TestPages.title(WorkspaceShell.pageOf(Destination.QUESTS)));
        assertEquals("Settings", TestPages.title(WorkspaceShell.pageOf(Destination.NOTIFICATIONS)));
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            assertEquals("Shells built directly still open on page 0", "chat", shell.selectedPage());
            shell.select("party");
            AbstractButton party = named(shell, "nav-party", AbstractButton.class);
            assertEquals("Party", party.getAccessibleContext().getAccessibleName());
            assertTrue(party.isSelected());
            assertEquals("page-party", shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_3, InputEvent.ALT_DOWN_MASK)));
        });
    }

    @Test public void altHOpensHomeAndBuildNeverTakesASidebarRowOrMenuEntry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            InputMap keys = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            assertEquals("page-home", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.ALT_DOWN_MASK)));
            assertEquals("Build keeps Alt+7", "page-my-info", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_7, InputEvent.ALT_DOWN_MASK)));
            assertEquals("Home  (Alt+H)", named(shell, "nav-home", AbstractButton.class).getToolTipText());
            JPopupMenu popup = named(shell, "compact-navigation", AbstractButton.class).getComponentPopupMenu();
            assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.ALT_DOWN_MASK), named(popup, "compact-nav-home", JMenuItem.class).getAccelerator());
            shell.getActionMap().get("page-home").actionPerformed(null);
            assertEquals("home", shell.selectedPage());
            assertEquals("Home", named(shell, "page-title", JLabel.class).getText());
            shell.getActionMap().get("page-my-info").actionPerformed(null);
            assertEquals("my-info", shell.selectedPage());
            assertEquals("Build", named(shell, "page-title", JLabel.class).getText());
            AbstractButton build = named(shell, "nav-my-info", AbstractButton.class);
            assertNotNull("The Build row exists for its name, selection and shortcut", build);
            assertTrue(build.isSelected());
            assertFalse("Build never shows in the sidebar, even while current", build.isVisible());
            assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat"), listedRows(shell));
            assertNull("Build is never added to the compact menu", named(popup, "compact-nav-my-info", JMenuItem.class));
            assertFalse(menuPages(popup).contains("my-info"));
            JPopupMenu menu = shell.contextMenu("my-info");
            for (String absent : new String[] {"nav-menu-move-up", "nav-menu-move-down", "nav-menu-hide", "nav-menu-show", "nav-menu-pin"})
                assertNull("Build has no row to customize: " + absent, item(menu, absent));
        });
    }


    @Test public void statisticsAndDpsLoggerKeepTheirPagesAndShortcutsButNoRowOrMenuEntry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            InputMap keys = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            assertEquals("Statistics keeps Alt+5", "page-statistics", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_5, InputEvent.ALT_DOWN_MASK)));
            assertEquals("DPS Logger keeps Alt+8", "page-dps-logger", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_8, InputEvent.ALT_DOWN_MASK)));
            AbstractButton runs = named(shell, "nav-runs", AbstractButton.class);
            assertEquals("Runs & DPS", runs.getText());
            assertEquals("Runs & DPS", runs.getAccessibleContext().getAccessibleName());
            assertEquals("Runs & DPS  (Alt+R)", runs.getToolTipText());
            JPopupMenu popup = named(shell, "compact-navigation", AbstractButton.class).getComponentPopupMenu();
            for (String page : new String[] {"statistics", "dps-logger"}) {
                shell.getActionMap().get("page-" + page).actionPerformed(null);
                assertEquals(page, shell.selectedPage());
                assertEquals(TestPages.title(page), named(shell, "page-title", JLabel.class).getText());
                AbstractButton row = named(shell, "nav-" + page, AbstractButton.class);
                assertNotNull("The row exists for its name, selection and shortcut", row);
                assertTrue(row.isSelected());
                assertFalse("Never listed in the sidebar, even while current", row.isVisible());
                assertEquals(Arrays.asList("home", "characters", "runs", "loot", "quests", "chat"), listedRows(shell));
                assertNull("Never added to the compact menu", named(popup, "compact-nav-" + page, JMenuItem.class));
                assertFalse(menuPages(popup).contains(page));
                JPopupMenu menu = shell.contextMenu(page);
                for (String absent : new String[] {"nav-menu-move-up", "nav-menu-move-down", "nav-menu-hide", "nav-menu-show", "nav-menu-pin"})
                    assertNull("No row to customize: " + absent, item(menu, absent));
            }
            assertEquals("Statistics", TestPages.title("statistics"));
            assertEquals("DPS Logger", TestPages.title("dps-logger"));
            assertEquals("Runs & DPS", TestPages.title("runs"));
        });
    }

    @Test public void aPageMapMustNameEveryDestinationExactlyAndSelectRejectsUnknownIds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, JComponent> missing = TestPages.placeholders(), extra = TestPages.placeholders(), stale = TestPages.placeholders();
            missing.remove("loot");
            extra.put("build", new JPanel());
            stale.remove("my-info"); stale.put("build", new JPanel());
            for (Map<String, JComponent> pages : Arrays.asList(missing, extra, stale)) {
                try {
                    new WorkspaceShell(pages, () -> {}, true, null, null, null, new NavLayout(store::get, store::put));
                    fail("A page map must name exactly the destinations: " + pages.keySet());
                } catch (IllegalArgumentException expected) {
                    assertEquals("All feature panels are required", expected.getMessage());
                }
            }
            WorkspaceShell shell = shell();
            for (String unknown : new String[] {"build", "advanced", "", null}) {
                try { shell.select(unknown); fail("Unknown page " + unknown); }
                catch (IllegalArgumentException expected) { assertEquals("Invalid page", expected.getMessage()); }
            }
            assertEquals("A rejected ID changes nothing", "chat", shell.selectedPage());
        });
    }

    @Test public void bindShortcutReplacesAPagesAltKeyWithANamedAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            InputMap keys = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            KeyStroke altSeven = KeyStroke.getKeyStroke(KeyEvent.VK_7, InputEvent.ALT_DOWN_MASK);
            assertEquals("page-my-info", keys.get(altSeven));
            int[] ran = new int[2];
            shell.bindShortcut(KeyEvent.VK_7, "open-build", () -> ran[0]++);
            assertEquals("The key names the new action", "open-build", keys.get(altSeven));
            shell.getActionMap().get(keys.get(altSeven)).actionPerformed(null);
            assertEquals(1, ran[0]);
            assertEquals("The replaced page is not selected", "chat", shell.selectedPage());
            shell.bindShortcut(KeyEvent.VK_7, "open-build-again", () -> ran[1]++);
            assertEquals("A later binding replaces the earlier one", "open-build-again", keys.get(altSeven));
            shell.getActionMap().get(keys.get(altSeven)).actionPerformed(null);
            assertArrayEquals(new int[] {1, 1}, ran);
            assertEquals("Other pages keep their keys", "page-runs", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK)));
            assertEquals("page-statistics", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_5, InputEvent.ALT_DOWN_MASK)));
        });
    }


    static void resize(WorkspaceShell shell, int width, int height) {
        shell.setSize(width, height);
        shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
        for (int pass = 0; pass < 4; pass++) layoutTree(shell);
    }

    static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container && child.isVisible()) layoutTree((Container) child);
    }

    static JMenuItem item(JPopupMenu menu, String name) {
        for (Component child : menu.getComponents()) {
            if (child instanceof JMenuItem && name.equals(child.getName())) return (JMenuItem) child;
            if (child instanceof JMenu)
                for (Component nested : ((JMenu) child).getMenuComponents())
                    if (nested instanceof JMenuItem && name.equals(nested.getName())) return (JMenuItem) nested;
        }
        return null;
    }

    static void click(JPopupMenu menu, String name) {
        JMenuItem found = item(menu, name);
        assertNotNull("Missing menu item " + name, found);
        assertTrue(name + " is enabled", found.isEnabled());
        found.doClick();
    }

    static void invoke(JComponent component, KeyStroke key) {
        Object binding = component.getInputMap(JComponent.WHEN_FOCUSED).get(key);
        assertNotNull("Missing binding " + key, binding);
        component.getActionMap().get(binding).actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, binding.toString()));
    }

    static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
