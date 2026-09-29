package tomato.gui.modern;

import java.awt.*;
import java.awt.dnd.DragSource;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.Tokens;
import static org.junit.Assert.*;
import static tomato.gui.modern.TestPages.listedRows;
import static tomato.gui.modern.TestPages.menuPages;
import static tomato.gui.modern.WorkspaceShellLayoutTest.layoutTree;
import static tomato.gui.modern.WorkspaceShellLayoutTest.named;
import static tomato.gui.modern.WorkspaceShellLayoutTest.resize;

/**
 * P6b sidebar drag (spec §4.1, §10): a core row dragged past the system threshold drops at the gap under the pointer, with a
 * violet line. Windowless like {@link WorkspaceShellLayoutTest} (one case shows a frame so the row menu can open), with synthetic
 * mouse events on the pressed row, as a real drag delivers them, and in-memory preferences that count their writes.
 */
public class WorkspaceShellDragTest {
    private static final KeyStroke ESCAPE = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
    private static final List<String> DEFAULT_ORDER = Arrays.asList("home", "characters", "runs", "loot", "quests", "chat");
    private final Map<String, String> store = new HashMap<>();
    private final List<String> writes = new ArrayList<>();

    /** Case 1: the drop lands at the gap, writes ORDER once, keeps the page and anchors the moved row. */
    @Test public void aDragPastTheThresholdDropsAtTheGapWritesOnceAndKeepsThePage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(1240, 800);
            shell.select("home");
            JToggleButton home = row(shell, "home"), characters = row(shell, "characters"), chat = row(shell, "chat");
            Point start = center(chat);
            press(chat, start);
            assertNull("A press alone shows no line", shell.dropIndicator());
            drag(chat, new Point(start.x, start.y - DragSource.getDragThreshold()));
            assertFalse("The drag disarms the row, so the release never selects it", chat.getModel().isArmed() || chat.getModel().isPressed());
            assertNull("Over its own place there is no line", shell.dropIndicator());
            Point target = over(chat, characters, 4);   // above Characters' centre: the gap between Home and Characters
            drag(chat, target);
            Rectangle line = shell.dropIndicator();
            assertNotNull("Dragging up shows the line", line);
            assertEquals("…in the gap below Home", home.getY() + home.getHeight(), line.y);
            assertEquals("…ending at the top of Characters", characters.getY(), line.y + line.height);
            assertEquals(2, line.height);
            assertTrue("Nothing is written during the drag", writes.isEmpty());
            release(chat, target);
            assertNull("The line clears on drop", shell.dropIndicator());
            assertEquals(Arrays.asList("home", "chat", "characters", "runs", "loot", "quests"), listedRows(shell));
            assertEquals("One ORDER write per drop", Collections.singletonList(NavLayout.ORDER_KEY), writes);
            assertEquals("home,chat,characters,runs,loot,quests", store.get(NavLayout.ORDER_KEY));
            assertEquals("The selected page is unchanged", "home", shell.selectedPage());
            assertTrue(home.isSelected());
            assertFalse(chat.isSelected());
            assertSame("The moved row stays in view", chat, shell.scrollAnchor());

            JToggleButton loot = row(shell, "loot");
            start = center(chat);
            press(chat, start);
            drag(chat, new Point(start.x, start.y + DragSource.getDragThreshold()));
            target = over(chat, loot, loot.getHeight() / 2 + 4);   // below Loot's centre: the gap between Loot and Quests
            drag(chat, target);
            line = shell.dropIndicator();
            assertNotNull("Dragging down shows the line", line);
            assertEquals("…at the bottom of Loot", loot.getY() + loot.getHeight(), line.y);
            release(chat, target);
            assertEquals(Arrays.asList("home", "characters", "runs", "loot", "chat", "quests"), listedRows(shell));
            assertEquals(Arrays.asList(NavLayout.ORDER_KEY, NavLayout.ORDER_KEY), writes);
            assertEquals("home", shell.selectedPage());
            assertSame(chat, shell.scrollAnchor());
        });
    }

    /** Case 2: movement under the threshold stays a click. */
    @Test public void twoPixelsOfJitterIsStillAClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertTrue("The system drag threshold exceeds 2 px", DragSource.getDragThreshold() > 2);
            WorkspaceShell shell = shell(1240, 800);
            shell.select("home");
            JToggleButton runs = row(shell, "runs");
            Point start = center(runs), jitter = new Point(start.x + 2, start.y + 2);
            press(runs, start);
            drag(runs, jitter);
            assertNull(shell.dropIndicator());
            assertTrue("The row is still pressed", runs.getModel().isPressed());
            release(runs, jitter);
            assertEquals("The click selects", "runs", shell.selectedPage());
            assertTrue("…and writes nothing", writes.isEmpty());
            assertEquals(DEFAULT_ORDER, listedRows(shell));
        });
    }

    /** Case 3: Esc, focus loss and a release outside the sidebar cancel: no write, no selection. */
    @Test public void escapeFocusLossAndAReleaseBesideTheSidebarCancel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(1240, 800);
            shell.select("home");
            JToggleButton chat = row(shell, "chat"), characters = row(shell, "characters");
            Object binding = chat.getInputMap(JComponent.WHEN_FOCUSED).get(ESCAPE);
            assertNotNull("Rows bind Esc", binding);
            Action escape = chat.getActionMap().get(binding);
            assertFalse("Without a drag Esc falls through to the page", escape.isEnabled());
            Point start = center(chat), target = over(chat, characters, 4);
            startDrag(chat, start, target);
            assertNotNull(shell.dropIndicator());
            assertTrue(escape.isEnabled());
            assertTrue("Esc is consumed mid-drag",
                SwingUtilities.notifyAction(escape, ESCAPE, new KeyEvent(chat, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED), chat, 0));
            assertNull("Esc clears the line", shell.dropIndicator());
            assertFalse(escape.isEnabled());
            release(chat, target);
            assertNull(shell.dropIndicator());

            startDrag(chat, start, target);
            assertNotNull(shell.dropIndicator());
            FocusEvent lost = new FocusEvent(chat, FocusEvent.FOCUS_LOST, true);
            for (FocusListener listener : chat.getFocusListeners()) listener.focusLost(lost);
            assertNull("Losing focus (Alt+Tab) cancels", shell.dropIndicator());
            release(chat, target);

            JScrollPane list = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, chat);
            Container sidebar = list.getParent();
            Point beside = SwingUtilities.convertPoint(sidebar, sidebar.getWidth() + 40, SwingUtilities.convertPoint(chat, target, sidebar).y, chat);
            startDrag(chat, start, target);
            drag(chat, beside);
            assertNull("Beside the sidebar there is no drop", shell.dropIndicator());
            drag(chat, target);
            assertNotNull("…until the pointer comes back", shell.dropIndicator());
            drag(chat, beside);
            release(chat, beside);
            assertNull(shell.dropIndicator());

            assertTrue("Nothing was written", writes.isEmpty());
            assertEquals(DEFAULT_ORDER, listedRows(shell));
            assertEquals("Nothing was selected", "home", shell.selectedPage());
            startDrag(chat, start, target);
            release(chat, target);
            assertEquals("A later drag still drops", Arrays.asList("home", "chat", "characters", "runs", "loot", "quests"), listedRows(shell));
        });
    }

    /** Case 4: only listed core rows drag, pinned Advanced rows included. */
    @Test public void onlyListedCoreRowsDragIncludingPinnedAdvancedRows() throws Exception {
        store.put(NavLayout.HIDDEN_KEY, "loot");
        store.put(NavLayout.PINNED_KEY, "timeline");
        store.put(NavLayout.ADVANCED_KEY, "true");
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(1240, 800);
            shell.select("loot");
            layoutTree(shell);
            assertTrue("The hidden current page is still listed", row(shell, "loot").isVisible());
            JToggleButton home = row(shell, "home");
            for (String id : new String[] {"loot", "settings", "party"}) {
                JToggleButton row = row(shell, id);
                Point start = center(row), target = over(row, home, 4);
                startDrag(row, start, target);
                assertNull(id + " does not drag", shell.dropIndicator());
                assertTrue(id + ": the button keeps its pressed model", row.getModel().isPressed());
                // A real drag leaves the row first, which disarms it; synthetic events need the exit spelled out.
                mouse(row, MouseEvent.MOUSE_EXITED, target, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON, false);
                release(row, target);
                assertTrue(id + " writes nothing", writes.isEmpty());
                assertEquals("loot", shell.selectedPage());
            }
            JToggleButton timeline = row(shell, "timeline");
            Point start = center(timeline), target = over(timeline, home, 4);
            startDrag(timeline, start, target);
            assertNotNull("A pinned Advanced row drags", shell.dropIndicator());
            release(timeline, target);
            assertEquals(Arrays.asList("timeline", "home", "characters", "runs", "loot", "quests", "chat"), listedRows(shell).subList(0, 7));
            assertEquals(Collections.singletonList(NavLayout.ORDER_KEY), writes);
            assertEquals("timeline,home,characters,runs,loot,quests,chat", store.get(NavLayout.ORDER_KEY));
            assertEquals("timeline", store.get(NavLayout.PINNED_KEY));
            assertEquals("loot", shell.selectedPage());
        });
    }

    /** Case 5: a drop over the Advanced group places the row last and never pins. */
    @Test public void aDropOverAdvancedPlacesTheRowLastAndNeverPins() throws Exception {
        store.put(NavLayout.PINNED_KEY, "timeline");
        store.put(NavLayout.ADVANCED_KEY, "true");
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(1240, 800);
            shell.select("home");
            JToggleButton home = row(shell, "home"), timeline = row(shell, "timeline"), logging = row(shell, "logging");
            Point start = center(home), target = over(home, logging, logging.getHeight() / 2);
            startDrag(home, start, target);
            Rectangle line = shell.dropIndicator();
            assertNotNull(line);
            assertEquals("The line sits below the last core row", timeline.getY() + timeline.getHeight(), line.y);
            release(home, target);
            assertEquals(Arrays.asList("characters", "runs", "loot", "quests", "chat", "timeline", "home", "party", "key-pops", "logging", "bridge-review"),
                listedRows(shell));
            assertEquals("PINNED is untouched", Collections.singletonList(NavLayout.ORDER_KEY), writes);
            assertEquals("timeline", store.get(NavLayout.PINNED_KEY));
            assertEquals("home", shell.selectedPage());
        });
    }

    /** Case 6: other buttons and popup triggers never drag; the row menu still opens. */
    @Test public void rightMiddleAndPopupTriggerDragsDoNothingAndTheMenuStillOpens() throws Exception {
        JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkspaceShell shell = shell(1240, 800);
                frame[0] = new JFrame("Navigation drag");
                frame[0].setContentPane(shell); frame[0].setSize(1240, 800); frame[0].setVisible(true);
                shell.select("home");
                layoutTree(shell);
                JToggleButton chat = row(shell, "chat");
                Point start = center(chat), target = over(chat, row(shell, "characters"), 4);
                int[][] buttons = {{InputEvent.BUTTON3_DOWN_MASK, MouseEvent.BUTTON3}, {InputEvent.BUTTON2_DOWN_MASK, MouseEvent.BUTTON2}};
                for (int[] button : buttons) {
                    mouse(chat, MouseEvent.MOUSE_PRESSED, start, button[0], button[1], false);
                    mouse(chat, MouseEvent.MOUSE_DRAGGED, target, button[0], MouseEvent.NOBUTTON, false);
                    assertNull("Button " + button[1] + " does not drag", shell.dropIndicator());
                    mouse(chat, MouseEvent.MOUSE_RELEASED, target, 0, button[1], false);
                }
                for (int[] button : new int[][] {{InputEvent.BUTTON3_DOWN_MASK, MouseEvent.BUTTON3}, {InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1}}) {
                    mouse(chat, MouseEvent.MOUSE_PRESSED, start, button[0], button[1], true);
                    MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
                    assertTrue("The popup trigger opens the row menu", path.length >= 1);
                    assertEquals("nav-menu", path[0].getComponent().getName());
                    mouse(chat, MouseEvent.MOUSE_DRAGGED, target, button[0], MouseEvent.NOBUTTON, false);
                    assertNull("A popup trigger does not drag", shell.dropIndicator());
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    mouse(chat, MouseEvent.MOUSE_EXITED, target, button[0], MouseEvent.NOBUTTON, false);
                    mouse(chat, MouseEvent.MOUSE_RELEASED, target, 0, button[1], false);
                }
                assertTrue(writes.isEmpty());
                assertEquals(DEFAULT_ORDER, listedRows(shell));
                assertEquals("home", shell.selectedPage());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                if (frame[0] != null) frame[0].dispose();
            });
        }
    }

    /** Case 7: in a 680×520 window at font 24 with Advanced open the list overflows; autoscroll moves it and nothing snaps it back. */
    @Test public void autoscrollAtTheCompactReferenceSizeDoesNotSnapBack() throws Exception {
        store.put(NavLayout.ADVANCED_KEY, "true");
        Font previous = ContentStyle.body();
        WorkspaceShell[] shell = new WorkspaceShell[1];
        int[] scrolled = new int[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 24));
                ContentStyle.applyFontDefaults();
                // A decorated 680×520 window (Windows: a 31 px title bar and 8 px borders) leaves 664×481 for the shell; a
                // windowless shell has no decorations, and at 680×520 its compact list (409 px) just fits the viewport (411 px).
                shell[0] = shell(664, 481);
                ContentStyle.refreshFonts(shell[0]);
                resize(shell[0], 664, 481);
                assertTrue(shell[0].isCompact());
                shell[0].select("home");
                layoutTree(shell[0]);
                JToggleButton home = row(shell[0], "home");
                JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, home);
                assertTrue("The list overflows: " + viewport.getViewSize() + " in " + viewport.getExtentSize(),
                    viewport.getViewSize().height > viewport.getExtentSize().height);
                assertEquals(0, viewport.getViewPosition().y);
                assertFalse("No drag, no autoscroll", shell[0].autoscrollStep());
                Point start = center(home), edge = SwingUtilities.convertPoint(viewport, viewport.getWidth() / 2, viewport.getHeight() - 4, home);
                startDrag(home, start, edge);
                assertTrue("Near the bottom edge a step scrolls", shell[0].autoscrollStep());
                assertTrue(viewport.getViewPosition().y > 0);
                for (int step = 0; step < 100 && shell[0].autoscrollStep(); step++) { }
                scrolled[0] = viewport.getViewPosition().y;
                assertEquals("…to the end", viewport.getViewSize().height - viewport.getExtentSize().height, scrolled[0]);
                assertFalse("The pressed row is out of view", viewport.getViewRect().intersects(home.getBounds()));
                layoutTree(shell[0]);   // doLayout queues scrollSelected, which ran on the next event
            });
            SwingUtilities.invokeAndWait(() -> {
                JToggleButton home = row(shell[0], "home"), chat = row(shell[0], "chat");
                JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, home);
                assertEquals("Nothing snapped the list back to the pressed row", scrolled[0], viewport.getViewPosition().y);
                Rectangle line = shell[0].dropIndicator();
                assertNotNull("Over the Advanced rows the drop goes last", line);
                assertEquals(chat.getY() + chat.getHeight(), line.y);
                release(home, SwingUtilities.convertPoint(viewport, viewport.getWidth() / 2, viewport.getHeight() - 4, home));
                assertEquals(Arrays.asList("characters", "runs", "loot", "quests", "chat", "home"), listedRows(shell[0]).subList(0, 6));
                assertEquals(Collections.singletonList(NavLayout.ORDER_KEY), writes);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                ContentStyle.setBodyFont(previous);
                ContentStyle.applyFontDefaults();
            });
        }
    }

    /** Case 8: the compact rail drags through the same code path, and the compact menu follows. */
    @Test public void theCompactRailDrags() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(800, 600);
            assertTrue(shell.isCompact());
            shell.select("home");
            JToggleButton chat = row(shell, "chat"), characters = row(shell, "characters");
            assertEquals("Icons only", "", chat.getText());
            Point start = center(chat), target = over(chat, characters, 4);
            startDrag(chat, start, target);
            assertNotNull(shell.dropIndicator());
            release(chat, target);
            assertEquals(Arrays.asList("home", "chat", "characters", "runs", "loot", "quests"), listedRows(shell));
            assertEquals(Collections.singletonList(NavLayout.ORDER_KEY), writes);
            JPopupMenu popup = named(shell, "compact-navigation", AbstractButton.class).getComponentPopupMenu();
            assertEquals("The compact menu is rebuilt", Arrays.asList("home", "chat", "characters"), menuPages(popup).subList(0, 3));
            assertEquals("home", shell.selectedPage());
        });
    }

    /** Case 9: the line is the theme's accent, resolved at paint time, so a live theme switch recolors it. */
    @Test public void theDropLineIsTheAccentInBothThemes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel previous = UIManager.getLookAndFeel(); boolean contrast = Themes.increaseContrast();
            try {
                assertTrue(Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
                WorkspaceShell shell = shell(1240, 800);
                shell.select("home");
                JToggleButton chat = row(shell, "chat"), characters = row(shell, "characters");
                Point start = center(chat), target = over(chat, characters, 4);
                startDrag(chat, start, target);
                Color dark = Tokens.color(Tokens.Role.ACCENT);
                assertEquals("Dark", dark.getRGB() & 0xFFFFFF, lineColor(shell));
                assertTrue(Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false)));
                SwingUtilities.updateComponentTreeUI(shell);
                resize(shell, 1240, 800);
                drag(chat, over(chat, characters, 4));
                Color light = Tokens.color(Tokens.Role.ACCENT);
                assertNotEquals("The themes differ", dark, light);
                assertEquals("Light, after a live switch mid-drag", light.getRGB() & 0xFFFFFF, lineColor(shell));
                release(chat, over(chat, characters, 4));
                assertEquals(Arrays.asList("home", "chat", "characters", "runs", "loot", "quests"), listedRows(shell));
            } finally {
                Themes.install(new Themes.Choice(previous instanceof VioletLightTheme ? Themes.Variant.LIGHT : Themes.Variant.DARK, contrast));
                if (!(previous instanceof VioletTheme || previous instanceof VioletLightTheme))
                    try { UIManager.setLookAndFeel(previous); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            }
        });
    }

    /** Rows show the keyboard alternatives in their accessible description (WCAG 2.2 SC 2.5.7); tooltips keep their pinned text. */
    @Test public void rowsDescribeTheKeyboardAlternatives() throws Exception {
        store.put(NavLayout.PINNED_KEY, "timeline");
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(1240, 800);
            for (String id : new String[] {"home", "chat", "timeline"})
                assertEquals(id, "Ctrl+Shift+Up or Down to move; Shift+F10 for options", row(shell, id).getAccessibleContext().getAccessibleDescription());
            for (String id : new String[] {"party", "settings"})
                assertEquals(id + " does not move", "Shift+F10 for options", row(shell, id).getAccessibleContext().getAccessibleDescription());
            assertEquals("Home  (Alt+H)", row(shell, "home").getToolTipText());
            WorkspaceShellLayoutTest.click(shell.contextMenu("party"), "nav-menu-pin");
            assertEquals("Pinned, it moves", "Ctrl+Shift+Up or Down to move; Shift+F10 for options",
                row(shell, "party").getAccessibleContext().getAccessibleDescription());
        });
    }

    private WorkspaceShell shell(int width, int height) {
        WorkspaceShell shell = new WorkspaceShell(TestPages.placeholders(), () -> {}, true, null, null, null,
            new NavLayout(store::get, (key, value) -> { writes.add(key); store.put(key, value); }));
        resize(shell, width, height);
        return shell;
    }

    private static JToggleButton row(WorkspaceShell shell, String id) {
        JToggleButton row = named(shell, "nav-" + id, JToggleButton.class);
        assertNotNull("No row " + id, row);
        return row;
    }

    private static Point center(JComponent row) { return new Point(row.getWidth() / 2, row.getHeight() / 2); }

    /** A point {@code dy} px below the top of {@code target}, in {@code source}'s coordinates. */
    private static Point over(JComponent source, JComponent target, int dy) {
        return SwingUtilities.convertPoint(target, target.getWidth() / 2, dy, source);
    }

    /** Presses {@code row} at {@code start}, moves just past the threshold, then to {@code target}. */
    private static void startDrag(JComponent row, Point start, Point target) {
        press(row, start);
        int threshold = DragSource.getDragThreshold();
        drag(row, new Point(start.x, start.y + (target.y < start.y ? -threshold : threshold)));
        drag(row, target);
    }

    private static void press(JComponent row, Point at) {
        mouse(row, MouseEvent.MOUSE_PRESSED, at, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1, false);
    }

    private static void drag(JComponent row, Point at) {
        mouse(row, MouseEvent.MOUSE_DRAGGED, at, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON, false);
    }

    private static void release(JComponent row, Point at) {
        mouse(row, MouseEvent.MOUSE_RELEASED, at, 0, MouseEvent.BUTTON1, false);
    }

    private static void mouse(JComponent row, int id, Point at, int modifiers, int button, boolean popupTrigger) {
        row.dispatchEvent(new MouseEvent(row, id, System.currentTimeMillis(), modifiers, at.x, at.y, 1, popupTrigger, button));
    }

    /** The painted colour at the middle of the drop line, as 0xRRGGBB. */
    private static int lineColor(WorkspaceShell shell) {
        Rectangle line = shell.dropIndicator();
        assertNotNull("A line to sample", line);
        JComponent nav = (JComponent) row(shell, "home").getParent();
        BufferedImage image = new BufferedImage(nav.getWidth(), nav.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        nav.paint(g);
        g.dispose();
        return image.getRGB(line.x + line.width / 2, line.y) & 0xFFFFFF;
    }
}
