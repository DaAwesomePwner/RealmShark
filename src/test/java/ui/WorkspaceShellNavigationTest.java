package ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.ui.FlatButtonUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.function.Predicate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import javax.swing.border.Border;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import static org.junit.Assert.*;

public class WorkspaceShellNavigationTest {
    private JFrame frame;
    private WorkspaceShell shell;
    private JComponent[] pages;
    private AbstractButton compactNavigation;
    private Font previousFont;
    private LookAndFeel previousLaf;
    private Robot robot;
    private Point previousMouse;

    @Before public void openShell() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousFont = ContentStyle.body(); previousLaf = UIManager.getLookAndFeel();
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, ContentStyle.FONT_SIZE));
            setLaf(new VioletTheme());
            pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) pages[i] = new JPanel();
            shell = new WorkspaceShell(pages, () -> {}, true);
            compactNavigation = button("compact-navigation");
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
        if (previousMouse != null) robot.mouseMove(previousMouse.x, previousMouse.y);
    }

    @Test public void selectedRowScrollsAfterNativeWindowResize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(14, WorkspaceShell.TITLES.length);
            for (int i = 0; i < 14; i++) {
                AbstractButton button = button("nav-" + i);
                assertEquals(WorkspaceShell.TITLES[i], button.getAccessibleContext().getAccessibleName());
                assertTrue(button.getHeight() >= 32);
                assertTrue("Default navigation should stay compact", button.getHeight() <= 36);
            }
            System.out.println("Native navigation initial client size=" + shell.getSize());
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
            resize(760, 620); shell.select(3); assertSelectedPage(3);
        });
        parkMouseAwayFromPopup();
        awaitFocus(compactNavigation, () -> compactNavigation.requestFocus());
        // Opening installs the menu bindings immediately, but root-pane focus arrives later.
        // BasicPopupMenuUI's Enter action does nothing while a button still owns focus.
        awaitFocus(frame.getRootPane(), () ->
            invokeKey(shell, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.ALT_DOWN_MASK)));
        // Enter first restores the invoker, then the destination listener focuses its row.
        awaitFocus(button("nav-4"), () -> {
            JPopupMenu popup = compactNavigation.getComponentPopupMenu();
            assertEquals("compact-navigation-popup", popup.getName()); assertTrue(popup.isShowing());
            assertEquals(14, popup.getComponentCount());
            for (int i = 0; i < 14; i++) {
                JMenuItem item = (JMenuItem) popup.getComponent(i);
                assertEquals(WorkspaceShell.TITLES[i], item.getText());
                assertNotNull(item.getIcon()); assertNotNull(item.getAccelerator());
            }
            assertSame(popup.getComponent(3), selectedMenuElement());
            menuKey(KeyEvent.VK_DOWN); assertSame(popup.getComponent(4), selectedMenuElement());
            assertSelectedPage(3);
            menuKey(KeyEvent.VK_ENTER);
            assertSelectedPage(4); assertFalse(popup.isVisible());
            assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
        });
        awaitFocus(compactNavigation, () -> compactNavigation.requestFocus());
        awaitFocus(frame.getRootPane(), () ->
            invokeKey(compactNavigation, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)));
        awaitFocus(compactNavigation, () -> {
            JPopupMenu popup = compactNavigation.getComponentPopupMenu();
            assertTrue(popup.isShowing()); assertSame(popup.getComponent(4), selectedMenuElement());
            menuKey(KeyEvent.VK_DOWN); assertSame(popup.getComponent(5), selectedMenuElement());
            assertSelectedPage(4);
            menuKey(KeyEvent.VK_ESCAPE);
            assertFalse(popup.isVisible()); assertEquals("Escape must not navigate", 4, shell.getSelectedPage());
            assertSelectedPage(4);
            assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
        });
        SwingUtilities.invokeAndWait(() -> {
            invokeKey(shell, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.ALT_DOWN_MASK));
            assertSelectedPage(13);
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
                invokeKey(frame.getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
                for (int i = 0; i < 14; i++) {
                    JMenuItem destination = (JMenuItem) popup.getComponent(i);
                    assertEquals(WorkspaceShell.TITLES[i], destination.getText());
                    invokeKey(shell, JComponent.WHEN_IN_FOCUSED_WINDOW, destination.getAccelerator());
                    assertSelectedPage(i);
                }
            }
        });
    }

    @Test public void realFrameFocusPaintDiffersFromSelectionAndReproducesTheOldEmptyBorderDefect() throws Exception {
        parkMouseAwayFromPopup();
        for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf(), new FlatDarkLaf()}) {
            for (int font : new int[] {13, 16, 24}) {
                SwingUtilities.invokeAndWait(() -> {
                    setLaf(laf); ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font));
                    ContentStyle.applyFontDefaults(); SwingUtilities.updateComponentTreeUI(frame); ContentStyle.refreshFonts(frame);
                    resize(760, 620); shell.select(0);
                });
                for (int index : new int[] {0, 1}) {
                    AbstractButton target = button("nav-" + index);
                    awaitFocus(target, () -> target.requestFocusInWindow());
                    SwingUtilities.invokeAndWait(() -> {
                        assertEquals(index == 0, target.isSelected());
                        assertTrue(FlatUIUtils.isPermanentFocusOwner(target));
                        java.util.Map<?, ?> style = (java.util.Map<?, ?>) target.getClientProperty("FlatLaf.style");
                        assertTrue("Every navigation style key is supported by the pinned FlatLaf",
                            ((FlatButtonUI) target.getUI()).getStyleableInfos(target).keySet().containsAll(style.keySet()));
                        target.getModel().setRollover(false);
                        Border border = target.getBorder();
                        try {
                            BufferedImage unfocused = focusImage(target, false), focused = focusImage(target, true);
                            int changed = changedPixels(unfocused, focused);
                            assertTrue(laf.getName() + ", " + font + "pt, selected=" + target.isSelected() + ": focus outline pixels=" + changed,
                                changed >= target.getWidth());
                            assertEquals(ContentStyle.color(index == 0 ? "selection" : "navigation"), target.getBackground());
                            if (laf instanceof VioletTheme) {
                                target.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                                assertEquals("The original EmptyBorder must reproduce invisible focus", 0,
                                    changedPixels(focusImage(target, false), focusImage(target, true)));
                            }
                        } finally {
                            target.setBorder(border); target.putClientProperty(FlatClientProperties.COMPONENT_FOCUS_OWNER, null);
                        }
                        System.out.println("Native focus evidence: actual shell=" + shell.getSize() + ", theme=" + laf.getName() + ", font=" + font);
                    });
                }
            }
        }
    }

    @Test public void exactOffscreenShellGeometryKeepsNavigationLabelsIconsAndBordersInsideTheSidebar() throws Exception {
        WorkspaceShell[] fixture = new WorkspaceShell[1];
        SwingUtilities.invokeAndWait(() -> {
            JComponent[] content = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < content.length; i++) content[i] = new JPanel();
            fixture[0] = new WorkspaceShell(content, () -> {}, true);
        });
        for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf(), new FlatDarkLaf()}) {
            for (int font : new int[] {13, 16, 24, 13}) for (Dimension geometry : new Dimension[] {new Dimension(1240, 800), new Dimension(680, 520)}) {
                SwingUtilities.invokeAndWait(() -> {
                    setLaf(laf); ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font)); ContentStyle.applyFontDefaults();
                    SwingUtilities.updateComponentTreeUI(fixture[0]); ContentStyle.refreshFonts(fixture[0]);
                    fixture[0].setSize(geometry); fixture[0].dispatchEvent(new ComponentEvent(fixture[0], ComponentEvent.COMPONENT_RESIZED));
                });
                for (int turn = 0; turn < 8; turn++) SwingUtilities.invokeAndWait(() -> layoutTree(fixture[0]));
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("Exact offscreen client geometry", geometry, fixture[0].getSize());
                    for (int i = 0; i < WorkspaceShell.TITLES.length; i++) {
                        AbstractButton target = (AbstractButton) find(fixture[0], "nav-" + i);
                        Insets insets = target.getInsets();
                        Rectangle available = new Rectangle(insets.left, insets.top,
                            target.getWidth() - insets.left - insets.right, target.getHeight() - insets.top - insets.bottom);
                        Rectangle icon = new Rectangle(), text = new Rectangle();
                        String rendered = SwingUtilities.layoutCompoundLabel(target, target.getFontMetrics(target.getFont()), target.getText(), target.getIcon(),
                            target.getVerticalAlignment(), target.getHorizontalAlignment(), target.getVerticalTextPosition(), target.getHorizontalTextPosition(),
                            available, icon, text, target.getIconTextGap());
                        assertEquals("No truncated navigation at " + geometry + ", " + font + "pt", target.getText(), rendered);
                        assertTrue("Full icon and focus insets", available.contains(icon));
                        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, target);
                        assertTrue("No horizontal sidebar clipping", target.getWidth() <= viewport.getExtentSize().width);
                        if (font == 13 && geometry.width == 1240)
                            assertEquals("All fourteen default rows fit the exact desktop client", target.getHeight(), target.getVisibleRect().height);
                    }
                    System.out.println("Offscreen shell geometry: " + geometry + ", theme=" + laf.getName() + ", font=" + font);
                });
            }
        }
    }

    @Test public void theSidebarWashReachesTheBaseColorBeforeTheDestinationListBegins() throws Exception {
        WorkspaceShell[] fixture = new WorkspaceShell[1];
        SwingUtilities.invokeAndWait(() -> {
            JComponent[] content = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < content.length; i++) content[i] = new JPanel();
            fixture[0] = new WorkspaceShell(content, () -> {}, true);
        });
        // A taller window lengthens a height-proportional fade, so the seam would widen with height.
        for (Dimension geometry : new Dimension[] {new Dimension(1240, 560), new Dimension(1240, 1100)}) {
            SwingUtilities.invokeAndWait(() -> {
                fixture[0].setSize(geometry);
                fixture[0].dispatchEvent(new ComponentEvent(fixture[0], ComponentEvent.COMPONENT_RESIZED));
            });
            for (int turn = 0; turn < 8; turn++) SwingUtilities.invokeAndWait(() -> layoutTree(fixture[0]));
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage image = new BufferedImage(geometry.width, geometry.height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                fixture[0].printAll(graphics);
                graphics.dispose();
                Component destination = find(fixture[0], "nav-0");
                Point origin = SwingUtilities.convertPoint(destination, 0, 0, fixture[0]);
                int base = image.getRGB(2, geometry.height - 4);
                // Sample the sidebar's leading edge, clear of the destinations and their scroll bar.
                for (int y = origin.y; y < geometry.height - 4; y++)
                    assertEquals("Banding seam at y=" + y + " for " + geometry,
                        base, image.getRGB(2, y));
            });
        }
    }

    private static BufferedImage focusImage(AbstractButton button, boolean focused) {
        button.putClientProperty(FlatClientProperties.COMPONENT_FOCUS_OWNER, (Predicate<JComponent>) component -> focused);
        assertEquals(focused, FlatUIUtils.isPermanentFocusOwner(button));
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics(); button.printAll(graphics); graphics.dispose(); return image;
    }
    private static int changedPixels(BufferedImage a, BufferedImage b) {
        int changed = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
            if (a.getRGB(x, y) != b.getRGB(x, y)) changed++;
        return changed;
    }
    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container && child.isVisible()) layoutTree((Container) child);
    }

    private void resize(int width, int height) {
        frame.setSize(width, height); frame.validate();
        shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED)); frame.validate();
    }
    private void parkMouseAwayFromPopup() throws Exception {
        robot = new Robot();
        previousMouse = MouseInfo.getPointerInfo().getLocation();
        Point point = new Point();
        SwingUtilities.invokeAndWait(() -> {
            point.setLocation(shell.getWidth() - 20, 20);
            SwingUtilities.convertPointToScreen(point, shell);
        });
        // A native mouse-enter must not replace the keyboard's highlighted menu item.
        robot.mouseMove(point.x, point.y);
        robot.waitForIdle();
    }
    private static void awaitFocus(Component component, Runnable trigger) throws Exception {
        CountDownLatch focused = new CountDownLatch(1);
        FocusListener listener = new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { focused.countDown(); }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                component.addFocusListener(listener);
                trigger.run();
                if (component.isFocusOwner()) focused.countDown();
            });
            assertTrue("Timed out waiting for keyboard focus", focused.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertSame("Keyboard focus owner", component,
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
        } finally {
            SwingUtilities.invokeAndWait(() -> component.removeFocusListener(listener));
        }
    }
    private void assertSelectedPage(int selected) {
        assertEquals(selected, shell.getSelectedPage());
        JPopupMenu popup = compactNavigation.getComponentPopupMenu();
        for (int i = 0; i < pages.length; i++) {
            assertEquals("Visible content page " + i, i == selected, pages[i].isShowing());
            assertEquals("Selected navigation row " + i, i == selected, button("nav-" + i).isSelected());
            assertEquals("Selected popup destination " + i, i == selected, ((JMenuItem) popup.getComponent(i)).isSelected());
        }
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
    private void menuKey(int keyCode) {
        assertSame("The popup must own keyboard input", frame.getRootPane(),
            KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        invokeKey(frame.getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(keyCode, 0));
    }
    private static MenuElement selectedMenuElement() {
        MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
        assertTrue("The popup must have a keyboard selection", path.length > 0);
        return path[path.length - 1];
    }
    private static void setLaf(LookAndFeel laf) {
        try { UIManager.setLookAndFeel(laf); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
    }
}
