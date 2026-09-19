package ui;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import static org.junit.Assert.*;

public class WorkspaceShellNavigationTest {
    private JFrame frame;
    private WorkspaceShell shell;
    private Font previousFont;
    private LookAndFeel previousLaf;

    @Before public void openShell() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousFont = ContentStyle.body(); previousLaf = UIManager.getLookAndFeel();
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, ContentStyle.FONT_SIZE));
            setLaf(new VioletTheme());
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) pages[i] = new JPanel();
            shell = new WorkspaceShell(pages, () -> {}, true);
            frame = new JFrame(); frame.setContentPane(shell);
            JMenuBar menu = new JMenuBar(); menu.add(new JMenu("File")); frame.setJMenuBar(menu);
            frame.setSize(1240, 800); frame.setVisible(true); resize(1240, 800);
        });
    }

    @After public void closeShell() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (frame != null) frame.dispose();
            ContentStyle.setBodyFont(previousFont); setLaf(previousLaf);
        });
    }

    @Test public void allFourteenRowsFitAtDesktopAndSelectedRowScrollsAfterResize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(14, WorkspaceShell.TITLES.length);
            assertFalse(shell.isCompact());
            for (int i = 0; i < 14; i++) {
                AbstractButton button = button("nav-" + i);
                assertEquals(WorkspaceShell.TITLES[i], button.getText());
                assertTrue(button.getHeight() >= 32);
                assertTrue("Default navigation should stay compact", button.getHeight() <= 36);
                assertEquals("Row must be fully visible: " + i, button.getHeight(), button.getVisibleRect().height);
            }
            shell.select(13); resize(680, 520);
        });
        // Let the resize-triggered scroll run after Swing has laid out the shorter viewport.
        SwingUtilities.invokeAndWait(() -> {
            AbstractButton selected = button("nav-13");
            assertTrue(shell.isCompact()); assertEquals(13, shell.getSelectedPage());
            assertEquals(selected.getHeight(), selected.getVisibleRect().height);
            assertTrue(button("compact-navigation").isShowing());
            JComponent content = (JComponent) find(shell, "workspace-content");
            assertEquals(new Insets(8, 8, 8, 8), content.getInsets());
        });
    }

    @Test public void compactPopupSupportsKeyboardSelectionEscapeAndExistingShortcuts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            resize(760, 620); shell.select(3);
            invokeKey(shell, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.ALT_DOWN_MASK));
            JPopupMenu popup = button("compact-navigation").getComponentPopupMenu();
            assertEquals("compact-navigation-popup", popup.getName()); assertTrue(popup.isShowing());
            assertEquals(14, popup.getComponentCount());
            for (int i = 0; i < 14; i++) {
                JMenuItem item = (JMenuItem) popup.getComponent(i);
                assertEquals(WorkspaceShell.TITLES[i], item.getText());
                assertNotNull(item.getIcon()); assertNotNull(item.getAccelerator());
            }
            assertSame(popup.getComponent(3), selectedMenuElement());
            menuAction("selectNext"); assertSame(popup.getComponent(4), selectedMenuElement());
            menuAction("return");
            assertEquals(4, shell.getSelectedPage()); assertFalse(popup.isVisible());

            invokeKey(button("compact-navigation"), JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0));
            assertTrue(popup.isVisible()); menuAction("selectNext"); menuAction("cancel");
            assertFalse(popup.isVisible()); assertEquals("Escape must not navigate", 4, shell.getSelectedPage());
            invokeKey(shell, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.ALT_DOWN_MASK));
            assertEquals(13, shell.getSelectedPage()); assertTrue(button("nav-13").isSelected());
            assertEquals("Notifications", button("nav-13").getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void lightThemeUsesSemanticShellColorsAndRetainsFontAndSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ContentStyle.refreshFonts(shell);
            ContentStyle.setBodyFont(new Font(Font.SERIF, Font.PLAIN, 18));
            shell.select(12);
            setLaf(new FlatLightLaf()); ContentStyle.applyFontDefaults();
            SwingUtilities.updateComponentTreeUI(frame); ContentStyle.refreshFonts(frame);
            assertEquals(UIManager.getColor("Panel.background"), shell.getBackground());
            assertEquals(UIManager.getColor("MenuBar.background"), button("nav-0").getBackground());
            assertEquals(UIManager.getColor("Table.selectionBackground"), button("nav-12").getBackground());
            assertEquals(UIManager.getColor("Table.selectionForeground"), button("nav-12").getForeground());
            assertEquals(18, button("nav-0").getFont().getSize()); assertEquals(12, shell.getSelectedPage());
            assertEquals(VioletTheme.CAPTURE_BACKGROUND, button("capture-toggle").getBackground());
        });
    }

    @Test public void compactMenuIconFitsAtDefaultAndLargeFontsAndRetainsEveryDestination() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ContentStyle.refreshFonts(shell);
            for (int size : new int[] {ContentStyle.FONT_SIZE, 24}) {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size));
                ContentStyle.refreshFonts(frame); resize(680, 720);
                AbstractButton menu = button("compact-navigation");
                assertTrue(menu.isShowing()); assertNotNull(menu.getIcon());
                assertTrue("The fixed-width menu uses an icon rather than truncatable text", menu.getText() == null || menu.getText().isEmpty());
                assertEquals("Choose workspace", menu.getAccessibleContext().getAccessibleName());
                Insets insets = menu.getInsets();
                Rectangle available = new Rectangle(insets.left, insets.top,
                    menu.getWidth() - insets.left - insets.right, menu.getHeight() - insets.top - insets.bottom);
                Rectangle icon = new Rectangle(), text = new Rectangle();
                SwingUtilities.layoutCompoundLabel(menu, menu.getFontMetrics(menu.getFont()), menu.getText(), menu.getIcon(),
                    menu.getVerticalAlignment(), menu.getHorizontalAlignment(), menu.getVerticalTextPosition(), menu.getHorizontalTextPosition(),
                    available, icon, text, menu.getIconTextGap());
                assertTrue("The complete menu icon must fit at " + size + "pt", available.contains(icon));
                assertTrue("The menu remains a usable desktop target", menu.getHeight() >= 32);
                invokeKey(shell, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.ALT_DOWN_MASK));
                JPopupMenu popup = menu.getComponentPopupMenu();
                assertTrue(popup.isVisible()); assertEquals(14, popup.getComponentCount());
                menuAction("cancel");
                for (int i = 0; i < 14; i++) {
                    JMenuItem destination = (JMenuItem) popup.getComponent(i);
                    assertEquals(WorkspaceShell.TITLES[i], destination.getText());
                    invokeKey(shell, JComponent.WHEN_IN_FOCUSED_WINDOW, destination.getAccelerator());
                    assertEquals(i, shell.getSelectedPage());
                }
            }
        });
    }

    private void resize(int width, int height) {
        frame.setSize(width, height); frame.validate();
        shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED)); frame.validate();
    }
    private AbstractButton button(String name) { return (AbstractButton) find(shell, name); }
    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container) { Component result = find((Container) child, name); if (result != null) return result; }
        }
        return null;
    }
    private static void invokeKey(JComponent component, int condition, KeyStroke key) {
        Object binding = component.getInputMap(condition).get(key); assertNotNull("Missing shortcut " + key, binding);
        Action action = component.getActionMap().get(binding); assertNotNull(action);
        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, binding.toString()));
    }
    private void menuAction(String name) {
        Action action = frame.getRootPane().getActionMap().get(name); assertNotNull("Missing menu keyboard action: " + name, action);
        action.actionPerformed(new ActionEvent(frame.getRootPane(), ActionEvent.ACTION_PERFORMED, name));
    }
    private static MenuElement selectedMenuElement() {
        MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
        return path[path.length - 1];
    }
    private static void setLaf(LookAndFeel laf) {
        try { UIManager.setLookAndFeel(laf); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
    }
}
