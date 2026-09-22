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
                if (frame != null) frame.dispose();
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
        frame.setTitle(title + " - synthetic validation"); frame.setContentPane(content);
        frame.setSize(width, height); frame.setVisible(true); UiTestLayout.settle(frame);
        System.out.println(title + " requested outer=" + width + "x" + height + ", actual outer=" + frame.getSize()
            + ", client=" + content.getSize() + ", font=" + font + ", transform=" + frame.getGraphicsConfiguration().getDefaultTransform());
    }

    /** Separate EDT turns also deliver pending width/document revalidation before measurement. */
    public void settle() throws Exception {
        assertFalse(SwingUtilities.isEventDispatchThread());
        for (int pass = 0; pass < 3; pass++) SwingUtilities.invokeAndWait(() -> UiTestLayout.settle(frame));
    }

    public void capture(String name) {
        assertTrue(SwingUtilities.isEventDispatchThread());
        UiTestLayout.settle(frame);
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); frame.printAll(graphics); graphics.dispose();
        try {
            File folder = new File("screenshots/wave1");
            assertTrue("Evidence directory", folder.isDirectory() || folder.mkdirs());
            assertTrue("PNG writer", ImageIO.write(image, "png", new File(folder, name + ".png")));
        } catch (Exception e) { throw new AssertionError(e); }
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
