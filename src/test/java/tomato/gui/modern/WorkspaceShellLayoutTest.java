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

/** Sidebar grouping, customization, compact menu and landing over in-memory preferences. */
public class WorkspaceShellLayoutTest {
    @Test public void contextMenuReordersHidesAndRestoresCoreDestinations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            click(shell.contextMenu(3), "nav-menu-move-up");
            assertEquals(Arrays.asList(3, 14, 10, 8, 5, 0), listedRows(shell));
            assertTrue(store.get(NavLayout.ORDER_KEY).startsWith("characters,home,"));
            assertFalse("The first row cannot move up", item(shell.contextMenu(3), "nav-menu-move-up").isEnabled());
            click(shell.contextMenu(8), "nav-menu-hide");
            assertFalse(named(shell, "nav-8", AbstractButton.class).isVisible());
            assertEquals("loot", store.get(NavLayout.HIDDEN_KEY));
            assertEquals(Arrays.asList(3, 14, 10, 5, 0), listedRows(shell));
            JMenu hidden = (JMenu) item(shell.contextMenu(3), "nav-menu-show-hidden");
            assertTrue(hidden.isEnabled());
            assertEquals("Loot", hidden.getItem(0).getText());
            click(shell.contextMenu(3), "nav-menu-show-8");
            assertTrue(named(shell, "nav-8", AbstractButton.class).isVisible());
            click(shell.contextMenu(14), "nav-menu-reset");
            assertEquals(Arrays.asList(14, 3, 10, 8, 5, 0), listedRows(shell));
            assertEquals("", store.get(NavLayout.ORDER_KEY));
            assertEquals("", store.get(NavLayout.HIDDEN_KEY));
        });
    }

    @Test public void advancedDestinationsPinToTheCoreListAndUnpinBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            assertNull("Core rows have nothing to pin", item(shell.contextMenu(14), "nav-menu-pin"));
            click(shell.contextMenu(11), "nav-menu-pin");
            assertEquals(Arrays.asList(14, 3, 10, 8, 5, 0, 11), listedRows(shell));
            assertEquals("Advanced (4)", named(shell, "nav-advanced", AbstractButton.class).getText());
            assertEquals("timeline", store.get(NavLayout.PINNED_KEY));
            click(shell.contextMenu(11), "nav-menu-move-up");
            assertEquals(Arrays.asList(14, 3, 10, 8, 5, 11, 0), listedRows(shell));
            click(shell.contextMenu(11), "nav-menu-unpin");
            assertEquals(Arrays.asList(14, 3, 10, 8, 5, 0), listedRows(shell));
            assertEquals("", store.get(NavLayout.PINNED_KEY));
            assertNotNull(item(shell.contextMenu(11), "nav-menu-pin"));
        });
    }

    @Test public void settingsOffersOnlyRestoreAndResetAndTheLastCoreRowStays() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            JPopupMenu settings = shell.contextMenu(13);
            assertEquals("nav-menu", settings.getName());
            for (String absent : new String[] {"nav-menu-move-up", "nav-menu-move-down", "nav-menu-hide", "nav-menu-pin"})
                assertNull(absent, item(settings, absent));
            assertFalse("Nothing is hidden yet", item(settings, "nav-menu-show-hidden").isEnabled());
            assertTrue(item(settings, "nav-menu-reset").isEnabled());
            for (int page : new int[] {14, 3, 10, 8, 5}) click(shell.contextMenu(page), "nav-menu-hide");
            assertFalse("The last visible core row cannot be hidden", item(shell.contextMenu(0), "nav-menu-hide").isEnabled());
        });
    }

    @Test public void focusedRowsMoveWithCtrlShiftArrowsAndOpenTheirMenuFromTheKeyboard() throws Exception {
        JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkspaceShell shell = shell();
                frame[0] = new JFrame("Navigation keyboard");
                frame[0].setContentPane(shell); frame[0].setSize(1240, 800); frame[0].setVisible(true);
                AbstractButton runs = named(shell, "nav-10", AbstractButton.class);
                invoke(runs, KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
                assertEquals(Arrays.asList(14, 10, 3, 8, 5, 0), listedRows(shell));
                invoke(runs, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
                assertEquals(Arrays.asList(14, 3, 10, 8, 5, 0), listedRows(shell));
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
            assertEquals(Arrays.asList(14, 3, 10, 5, 0, 11, 2, 1, 9, 12, 13), menuPages(popup));
            JLabel group = named(popup, "compact-nav-advanced", JLabel.class);
            assertTrue(group.isVisible());
            assertEquals("Advanced", group.getText());
            int separators = 0;
            for (Component child : popup.getComponents()) if (child instanceof JSeparator && child.isVisible()) separators++;
            assertEquals("Core | Advanced | Settings", 2, separators);
            shell.select(8);
            assertEquals("The current page is listed even when hidden",
                Arrays.asList(14, 3, 10, 8, 5, 0, 11, 2, 1, 9, 12, 13), menuPages(popup));
            assertTrue(named(popup, "compact-nav-8", JMenuItem.class).isSelected());
            click(shell.contextMenu(0), "nav-menu-move-up");
            assertEquals("Reordering reaches the menu",
                Arrays.asList(14, 3, 10, 8, 0, 5, 11, 2, 1, 9, 12, 13), menuPages(popup));
        });
    }
    @Test public void landingIsTheFirstVisibleCoreDestinationInTheUsersOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            assertEquals("Shells built directly still open on page 0", 0, shell.getSelectedPage());
            shell.selectLanding();
            assertEquals("Home is the default landing page", 14, shell.getSelectedPage());
            click(shell.contextMenu(14), "nav-menu-hide");
            shell.selectLanding();
            assertEquals("Hidden rows are skipped", 3, shell.getSelectedPage());
            store.put(NavLayout.ORDER_KEY, "quests,home");
            WorkspaceShell reordered = shell();
            reordered.selectLanding();
            assertEquals("A reordered list lands on its new first row", 5, reordered.getSelectedPage());
            store.remove(NavLayout.HIDDEN_KEY);
            store.put(NavLayout.ORDER_KEY, "quests");
            WorkspaceShell upgraded = shell();
            upgraded.selectLanding();
            assertEquals("A saved order from before Home gains Home in front", 14, upgraded.getSelectedPage());
            assertEquals("Reading never rewrites the saved order", "quests", store.get(NavLayout.ORDER_KEY));
        });
    }
    @Test public void hidingARowNeverAnchorsOnAHiddenRowAndFocusFallsToThePage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) pages[i] = new JPanel();
            JButton build = new JButton("Estimate");
            pages[6].add(build);
            WorkspaceShell shell = new WorkspaceShell(pages, () -> {}, true, null, null, null, new NavLayout(store::get, store::put));
            JFrame frame = new JFrame("Hidden row focus");
            frame.setContentPane(shell); frame.setSize(1240, 800); frame.setVisible(true);   // the focus traversal policy orders only a showing window
            try {
                shell.select(6);   // Build: unlisted, no row
                click(shell.contextMenu(8), "nav-menu-hide");
                assertFalse(named(shell, "nav-8", AbstractButton.class).isVisible());
                assertNull("No visible row to anchor on while Build is current", shell.scrollAnchor());
                assertSame("Focus goes into Build, which has no row", build, shell.focusTarget(6));
                click(shell.contextMenu(3), "nav-menu-show-8");
                shell.select(8);
                click(shell.contextMenu(8), "nav-menu-hide");   // the current page's own row
                AbstractButton lootRow = named(shell, "nav-8", AbstractButton.class);
                assertTrue("The current page's row stays listed while it is current", lootRow.isVisible());
                assertSame("…so it still anchors and takes focus", lootRow, shell.scrollAnchor());
                assertSame(lootRow, shell.focusTarget(8));
                shell.select(3);
                assertFalse("Once another page is current, the hidden row leaves the sidebar", lootRow.isVisible());
                click(shell.contextMenu(10), "nav-menu-hide");
                JToggleButton characters = named(shell, "nav-3", JToggleButton.class);
                assertSame("A visible current row anchors and takes focus", characters, shell.scrollAnchor());
                assertSame(characters, shell.focusTarget(3));
            } finally { frame.dispose(); }
        });
    }

    private final Map<String, String> store = new HashMap<>();

    private WorkspaceShell shell() {
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
        for (int i = 0; i < pages.length; i++) pages[i] = new JPanel();
        WorkspaceShell shell = new WorkspaceShell(pages, () -> {}, true, null, null, null, new NavLayout(store::get, store::put));
        resize(shell, 1240, 800);
        return shell;
    }

    @Test public void coreDestinationsComeFirstAndAdvancedStartsCollapsed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            assertEquals(Arrays.asList(14, 3, 10, 8, 5, 0), listedRows(shell));
            AbstractButton advanced = named(shell, "nav-advanced", AbstractButton.class);
            assertEquals("Advanced (5)", advanced.getText());
            assertEquals("Advanced pages", advanced.getAccessibleContext().getAccessibleName());
            assertEquals("Collapsed", advanced.getAccessibleContext().getAccessibleDescription());
            advanced.doClick();
            assertEquals("true", store.get(NavLayout.ADVANCED_KEY));
            assertEquals("Expanded", advanced.getAccessibleContext().getAccessibleDescription());
            assertEquals(Arrays.asList(14, 3, 10, 8, 5, 0, 2, 1, 11, 9, 12), listedRows(shell));
            assertEquals("The open group is remembered", Arrays.asList(14, 3, 10, 8, 5, 0, 2, 1, 11, 9, 12), listedRows(shell()));
        });
    }

    @Test public void settingsStaysBelowTheScrollingListAndOpensWithAltComma() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            AbstractButton settings = named(shell, "nav-13", AbstractButton.class);
            assertTrue(settings.isVisible());
            assertNull("Settings is outside the scrolling list", SwingUtilities.getAncestorOfClass(JViewport.class, settings));
            assertEquals("Settings", settings.getText());
            assertEquals("Settings", settings.getAccessibleContext().getAccessibleName());
            assertTrue(settings.getToolTipText(), settings.getToolTipText().contains("Alt+,"));
            Object binding = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.ALT_DOWN_MASK));
            assertEquals("open-settings", binding);
            shell.getActionMap().get(binding).actionPerformed(null);
            assertEquals(13, shell.getSelectedPage());
            assertEquals("page-13", shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.ALT_DOWN_MASK)));
        });
    }

    @Test public void theCurrentPageStaysVisibleWhileCollapsedOrHidden() throws Exception {
        store.put(NavLayout.HIDDEN_KEY, "loot");
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            AbstractButton bridge = named(shell, "nav-12", AbstractButton.class), loot = named(shell, "nav-8", AbstractButton.class);
            assertFalse(bridge.isVisible());
            assertFalse(loot.isVisible());
            shell.select(12);
            assertTrue("A collapsed Advanced page shows while it is current", bridge.isVisible());
            assertTrue(bridge.isSelected());
            assertEquals(Arrays.asList(14, 3, 10, 5, 0, 12), listedRows(shell));
            shell.select(8);
            assertTrue("A hidden page shows while it is current", loot.isVisible());
            assertFalse(bridge.isVisible());
            shell.select(6);
            assertFalse(loot.isVisible());
            assertFalse("Build never shows in the sidebar, even while current", named(shell, "nav-6", AbstractButton.class).isVisible());
        });
    }

    @Test public void titlesAndRoutesKeepTheirPageIndices() throws Exception {
        assertEquals(15, WorkspaceShell.TITLES.length);
        assertEquals(14, WorkspaceShell.pageOf(Destination.HOME));
        assertEquals(6, WorkspaceShell.pageOf(Destination.MY_INFO));
        assertEquals("Home", WorkspaceShell.TITLES[14]);
        assertEquals("Build", WorkspaceShell.TITLES[6]);
        assertEquals("Party", WorkspaceShell.TITLES[WorkspaceShell.pageOf(Destination.INSPECT)]);
        assertEquals("Quests", WorkspaceShell.TITLES[WorkspaceShell.pageOf(Destination.QUESTS)]);
        assertEquals("Settings", WorkspaceShell.TITLES[WorkspaceShell.pageOf(Destination.NOTIFICATIONS)]);
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            assertEquals("Shells built directly still open on page 0", 0, shell.getSelectedPage());
            shell.select(2);
            AbstractButton party = named(shell, "nav-2", AbstractButton.class);
            assertEquals("Party", party.getAccessibleContext().getAccessibleName());
            assertTrue(party.isSelected());
            assertEquals("page-2", shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_3, InputEvent.ALT_DOWN_MASK)));
        });
    }

    @Test public void altHOpensHomeAndBuildNeverTakesASidebarRowOrMenuEntry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            InputMap keys = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            assertEquals("page-14", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.ALT_DOWN_MASK)));
            assertEquals("Build keeps Alt+7", "page-6", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_7, InputEvent.ALT_DOWN_MASK)));
            assertEquals("Home  (Alt+H)", named(shell, "nav-14", AbstractButton.class).getToolTipText());
            JPopupMenu popup = named(shell, "compact-navigation", AbstractButton.class).getComponentPopupMenu();
            assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.ALT_DOWN_MASK), named(popup, "compact-nav-14", JMenuItem.class).getAccelerator());
            shell.getActionMap().get("page-14").actionPerformed(null);
            assertEquals(14, shell.getSelectedPage());
            assertEquals("Home", named(shell, "page-title", JLabel.class).getText());
            shell.getActionMap().get("page-6").actionPerformed(null);
            assertEquals(6, shell.getSelectedPage());
            assertEquals("Build", named(shell, "page-title", JLabel.class).getText());
            AbstractButton build = named(shell, "nav-6", AbstractButton.class);
            assertNotNull("The Build row exists for its name, selection and shortcut", build);
            assertTrue(build.isSelected());
            assertFalse("Build never shows in the sidebar, even while current", build.isVisible());
            assertEquals(Arrays.asList(14, 3, 10, 8, 5, 0), listedRows(shell));
            assertNull("Build is never added to the compact menu", named(popup, "compact-nav-6", JMenuItem.class));
            assertFalse(menuPages(popup).contains(6));
            JPopupMenu menu = shell.contextMenu(6);
            for (String absent : new String[] {"nav-menu-move-up", "nav-menu-move-down", "nav-menu-hide", "nav-menu-show", "nav-menu-pin"})
                assertNull("Build has no row to customize: " + absent, item(menu, absent));
        });
    }


    @Test public void statisticsAndDpsLoggerKeepTheirPagesAndShortcutsButNoRowOrMenuEntry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell();
            InputMap keys = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            assertEquals("Statistics keeps Alt+5", "page-4", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_5, InputEvent.ALT_DOWN_MASK)));
            assertEquals("DPS Logger keeps Alt+8", "page-7", keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_8, InputEvent.ALT_DOWN_MASK)));
            AbstractButton runs = named(shell, "nav-10", AbstractButton.class);
            assertEquals("Runs & DPS", runs.getText());
            assertEquals("Runs & DPS", runs.getAccessibleContext().getAccessibleName());
            assertEquals("Runs & DPS  (Alt+R)", runs.getToolTipText());
            JPopupMenu popup = named(shell, "compact-navigation", AbstractButton.class).getComponentPopupMenu();
            for (int page : new int[] {4, 7}) {
                shell.getActionMap().get("page-" + page).actionPerformed(null);
                assertEquals(page, shell.getSelectedPage());
                assertEquals(WorkspaceShell.TITLES[page], named(shell, "page-title", JLabel.class).getText());
                AbstractButton row = named(shell, "nav-" + page, AbstractButton.class);
                assertNotNull("The row exists for its name, selection and shortcut", row);
                assertTrue(row.isSelected());
                assertFalse("Never listed in the sidebar, even while current", row.isVisible());
                assertEquals(Arrays.asList(14, 3, 10, 8, 5, 0), listedRows(shell));
                assertNull("Never added to the compact menu", named(popup, "compact-nav-" + page, JMenuItem.class));
                assertFalse(menuPages(popup).contains(page));
                JPopupMenu menu = shell.contextMenu(page);
                for (String absent : new String[] {"nav-menu-move-up", "nav-menu-move-down", "nav-menu-hide", "nav-menu-show", "nav-menu-pin"})
                    assertNull("No row to customize: " + absent, item(menu, absent));
            }
            assertEquals("Statistics", WorkspaceShell.TITLES[4]);
            assertEquals("DPS Logger", WorkspaceShell.TITLES[7]);
            assertEquals("Runs & DPS", WorkspaceShell.TITLES[10]);
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

    /** Pages of the visible rows in the scrolling list, top to bottom (Settings is below the list). */
    static List<Integer> listedRows(WorkspaceShell shell) {
        for (int pass = 0; pass < 2; pass++) layoutTree(shell);
        List<AbstractButton> rows = new ArrayList<>();
        for (int page = 0; page < WorkspaceShell.TITLES.length; page++) {
            AbstractButton row = named(shell, "nav-" + page, AbstractButton.class);
            if (row.isVisible() && SwingUtilities.getAncestorOfClass(JViewport.class, row) != null) rows.add(row);
        }
        rows.sort(Comparator.comparingInt(row -> SwingUtilities.convertPoint(row, 0, 0, shell).y));
        List<Integer> pages = new ArrayList<>();
        for (AbstractButton row : rows) pages.add(Integer.parseInt(row.getName().substring("nav-".length())));
        return pages;
    }

    /** Pages of the destinations the compact menu lists, in menu order. */
    static List<Integer> menuPages(JPopupMenu popup) {
        List<Integer> pages = new ArrayList<>();
        for (Component item : popup.getComponents())
            if (item instanceof JMenuItem && item.isVisible()) pages.add(Integer.parseInt(item.getName().substring("compact-nav-".length())));
        return pages;
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
