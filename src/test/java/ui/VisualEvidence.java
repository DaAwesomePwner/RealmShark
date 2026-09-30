package ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import org.junit.rules.ExternalResource;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import static org.junit.Assert.*;

/** Native synthetic evidence; measurements use the realized geometry, never an enlarged workaround. */
public final class VisualEvidence extends ExternalResource {
    private JFrame frame;
    private Font previousFont;
    private LookAndFeel previousTheme;
    private final String folder;

    public VisualEvidence() { this("wave1"); }
    public VisualEvidence(String folder) { this.folder = folder; }

    @Override protected void before() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousFont = ContentStyle.body(); previousTheme = UIManager.getLookAndFeel();
            VioletTheme.install();
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13));
            ContentStyle.applyFontDefaults();
        });
    }

    @Override protected void after() {
        try {
            SwingUtilities.invokeAndWait(() -> {
                release();
                ContentStyle.setBodyFont(previousFont);
                try { UIManager.setLookAndFeel(previousTheme); }
                catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
                ContentStyle.applyFontDefaults();
            });
        } catch (Exception e) { throw new AssertionError(e); }
    }

    public void show(JComponent content, String title, int width, int height, int font) {
        assertTrue(SwingUtilities.isEventDispatchThread());
        ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font));
        ContentStyle.applyFontDefaults(); ContentStyle.refreshFonts(content);
        if (frame == null) frame = new JFrame();
        frame.setTitle(title + " - synthetic validation");
        if (frame.getContentPane() != content) frame.setContentPane(content);
        frame.setSize(width, height); frame.setVisible(true); UiTestLayout.settle(frame);
        System.out.println(title + " requested outer=" + width + "x" + height + ", actual outer=" + frame.getSize()
            + ", client=" + content.getSize() + ", font=" + font + ", transform=" + frame.getGraphicsConfiguration().getDefaultTransform());
    }

    /** Separate EDT turns also deliver pending width/document revalidation before measurement. */
    public void settle() throws Exception {
        assertFalse(SwingUtilities.isEventDispatchThread());
        for (int pass = 0; pass < 3; pass++) SwingUtilities.invokeAndWait(() -> { if (frame != null) UiTestLayout.settle(frame); });
    }

    public void capture(String name) {
        capture(frame, name);
    }

    public void capture(Window window, String name) {
        assertTrue(SwingUtilities.isEventDispatchThread());
        UiTestLayout.settle(window);
        BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); window.printAll(graphics); graphics.dispose();
        write(image, name);
    }

    /**
     * Paints the shown frame's root pane, not the window: on XToolkit {@code Window.printAll} paints a title band and edges over
     * the content even when the frame has no insets, and the root pane has neither, so the image is exactly the root pane's size
     * with nothing padded or covered. Lightweight popups live in its layered pane and are included; heavyweight popups (the
     * look and feel's menus, such as a Scope or navigation menu) are windows the frame owns, painted over the image at their place.
     */
    public void captureRoot(String name) {
        assertTrue(SwingUtilities.isEventDispatchThread());
        assertNotNull("A shown frame", frame);
        JRootPane root = frame.getRootPane();
        UiTestLayout.settle(frame);
        BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); root.printAll(graphics);
        Point origin = root.getLocationOnScreen();
        for (Window owned : frame.getOwnedWindows())
            if (owned.isShowing() && owned instanceof RootPaneContainer) {
                JRootPane popup = ((RootPaneContainer) owned).getRootPane();
                Point at = popup.getLocationOnScreen();
                Graphics2D over = (Graphics2D) graphics.create(at.x - origin.x, at.y - origin.y, popup.getWidth(), popup.getHeight());
                popup.printAll(over); over.dispose();
            }
        graphics.dispose();
        write(image, name);
    }

    private void write(BufferedImage image, String name) {
        try {
            File directory = new File("screenshots", folder);
            assertTrue("Evidence directory", directory.isDirectory() || directory.mkdirs());
            assertTrue("PNG writer", ImageIO.write(image, "png", new File(directory, name + ".png")));
        } catch (Exception e) { throw new AssertionError(e); }
    }

    /**
     * No page scrolls or cuts its content sideways: every showing scroll pane under {@code root} has no horizontal scroll bar and a
     * view no wider than its viewport. A data table scrolls its own columns sideways by design, and a report text area that does
     * not wrap its lines (a hit report, a raw log) scrolls them: both are listed on standard output instead (as in P3b–P6a).
     */
    public static void nothingSideways(Container root, String capture) {
        for (Component child : root.getComponents()) {
            if (child instanceof JScrollPane && child.isShowing()) {
                JScrollPane scroll = (JScrollPane) child;
                Component view = scroll.getViewport().getView();
                String where = scroll.getName() != null ? scroll.getName() : view == null ? "an empty scroll pane" : view.getClass().getSimpleName();
                boolean report = view instanceof JTextArea && !((JTextArea) view).getLineWrap();
                if (view instanceof JTable || report) System.out.println(capture + ": " + (report ? "report " : "table ") + where + " " + view.getWidth() + " px in a "
                    + scroll.getViewport().getWidth() + " px viewport, horizontal bar " + (scroll.getHorizontalScrollBar().isShowing() ? "shown" : "hidden"));
                else {
                    assertFalse(capture + ": a horizontal scroll bar in " + where, scroll.getHorizontalScrollBar().isShowing());
                    if (view != null) assertTrue(capture + ": " + where + " is " + view.getWidth() + " px wide in a " + scroll.getViewport().getWidth() + " px viewport",
                        view.getWidth() <= scroll.getViewport().getWidth());
                }
            }
            if (child instanceof Container) nothingSideways((Container) child, capture);
        }
    }

    public void closeWindow() {
        assertTrue(SwingUtilities.isEventDispatchThread());
        release();
    }

    /**
     * Disposes the frame and detaches its content. A disposed frame stays in {@code Window.getWindows()} until it is collected,
     * and the next test's theme install updates the UI of every window: a button's mnemonic in the old content (a window-wide
     * key binding) would then be registered again in Swing's static KeyboardManager under the disposed frame, which keeps the
     * frame, its workspace and its capture data alive for the rest of the test run.
     */
    private void release() {
        if (frame == null) return;
        frame.dispose();
        frame.setContentPane(new JPanel());
        frame = null;
    }

    public static void reachable(JComponent component) {
        reachable(component, new Rectangle(0, 0, component.getWidth(), component.getHeight()));
    }

    public static void reachable(JComponent component, Rectangle region) {
        assertTrue("Allocated visible component: " + component.getName(), component.isShowing() && component.getWidth() > 0 && component.getHeight() > 0);
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof JViewport) {
                JComponent view = (JComponent)((JViewport)parent).getView();
                view.scrollRectToVisible(SwingUtilities.convertRectangle(component, region, view));
            }
        }
        assertTrue("Unreachable " + component.getName() + ": " + region + " in " + component.getVisibleRect(),
            component.getVisibleRect().contains(region));
    }

    public static void completeButton(AbstractButton button) {
        Insets insets = button.getInsets();
        Rectangle available = new Rectangle(insets.left, insets.top,
            button.getWidth() - insets.left - insets.right, button.getHeight() - insets.top - insets.bottom);
        Icon icon = button.getIcon();
        if (icon == null && button instanceof JCheckBox) icon = UIManager.getIcon("CheckBox.icon");
        Rectangle iconBounds = new Rectangle(), textBounds = new Rectangle();
        String rendered = SwingUtilities.layoutCompoundLabel(button, button.getFontMetrics(button.getFont()), button.getText(), icon,
            button.getVerticalAlignment(), button.getHorizontalAlignment(), button.getVerticalTextPosition(), button.getHorizontalTextPosition(),
            available, iconBounds, textBounds, button.getIconTextGap());
        System.out.println("Control '" + button.getText() + "': bounds=" + button.getBounds() + ", text=" + textBounds + ", available=" + available);
        assertEquals("Complete control label", button.getText(), rendered);
        assertTrue("Control text height: " + button.getText(), available.contains(textBounds));
        reachable(button);
    }

    public static void completeText(JTextArea area) {
        Dimension needed = area.getUI().getPreferredSize(area);
        System.out.println("Text '" + area.getName() + "': allocated=" + area.getSize() + ", needed=" + needed + ", visible=" + area.getVisibleRect());
        assertTrue("Full text height: " + area.getName() + " allocated=" + area.getHeight() + " needed=" + needed.height,
            area.getHeight() >= needed.height);
        try {
            Rectangle first = area.modelToView(0), last = area.modelToView(area.getDocument().getLength());
            assertNotNull(first); assertNotNull(last);
            reachable(area, first); reachable(area, last);
        } catch (BadLocationException e) { throw new AssertionError(e); }
    }

    public static <T extends Component> T find(Container root, Class<T> type, Predicate<T> predicate) {
        T found = search(root, type, predicate);
        assertNotNull("Missing " + type.getSimpleName(), found); return found;
    }

    private static <T extends Component> T search(Container root, Class<T> type, Predicate<T> predicate) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && predicate.test(type.cast(child))) return type.cast(child);
            if (child instanceof Container) {
                T found = search((Container)child, type, predicate); if (found != null) return found;
            }
        }
        return null;
    }

    public static <T extends Component> T named(Container root, String name, Class<T> type) {
        return find(root, type, component -> name.equals(component.getName()));
    }

    public static AbstractButton button(Container root, String text) {
        return find(root, AbstractButton.class, component -> text.equals(component.getText()));
    }
}
