package ui;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.TableModel;
import javax.swing.text.JTextComponent;
import static org.junit.Assert.*;

/**
 * Wave 3 visual evidence: each surface is shown in the real native frame (VisualEvidence), settled, then
 * painted at the realized device scale so 150%/200% captures show the pixels a user would see. Output goes to
 * {@code screenshots/wave3} under the test task's working directory (ui-test, ui-Ui150 or ui-Ui200).
 */
public final class WaveThreeEvidence {
    public static final String FOLDER = "wave3";
    /** A realistic synthetic epoch (2026-09-21) so captured dates read like recorded history, not 1970. */
    public static final long BASE = 1_790_000_000_000L;
    public static final int WIDE_WIDTH = 1240, WIDE_HEIGHT = 800, COMPACT_WIDTH = 680, COMPACT_HEIGHT = 520, FONT = 13;
    private WaveThreeEvidence() {}

    public interface Check { void run() throws Exception; }
    public interface Checked<T> { T get() throws Exception; }

    public static <T> T edt(Checked<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(body.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    public static void run(Check body) throws Exception { edt(() -> { body.run(); return null; }); }

    /** Shows {@code root} wide (1240) and compact (680, below the 1000 px shell breakpoint), capturing each after assertions. */
    public static void wideAndCompact(VisualEvidence evidence, JComponent root, String name, Check assertions) throws Exception {
        frame(evidence, root, name, false, assertions);
        frame(evidence, root, name, true, assertions);
    }

    public static void frame(VisualEvidence evidence, JComponent root, String name, boolean compact, Check assertions) throws Exception {
        frame(evidence, root, name, compact, () -> true, assertions);
    }

    /** Like {@link #wideAndCompact(VisualEvidence, JComponent, String, Check)}, awaiting asynchronous content after each resize. */
    public static void wideAndCompact(VisualEvidence evidence, JComponent root, String name, BooleanSupplier ready, Check assertions) throws Exception {
        frame(evidence, root, name, false, ready, assertions);
        frame(evidence, root, name, true, ready, assertions);
    }

    public static void frame(VisualEvidence evidence, JComponent root, String name, boolean compact, BooleanSupplier ready, Check assertions) throws Exception {
        run(() -> evidence.show(root, name, compact ? COMPACT_WIDTH : WIDE_WIDTH, compact ? COMPACT_HEIGHT : WIDE_HEIGHT, FONT));
        evidence.settle();
        await(ready);
        evidence.settle();
        await(ready);
        run(() -> {
            if (assertions != null) assertions.run();
            capture(SwingUtilities.getWindowAncestor(root), name + (compact ? "-compact" : "-wide"));
        });
    }

    /** Polls on the EDT (the test thread sleeps between polls) until {@code condition} holds; asynchronous reads keep completing. */
    public static void await(BooleanSupplier condition) throws Exception {
        assertFalse("Wait outside the EDT", SwingUtilities.isEventDispatchThread());
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!edt(condition::getAsBoolean)) {
            assertTrue("Timed out waiting for evidence state", System.nanoTime() < until);
            Thread.sleep(20);
        }
    }

    /** Resizes an application dialog/window to wide and compact sizes and captures each; the window stays open. */
    public static void windowWideAndCompact(Window window, String name, int wideWidth, int wideHeight, Check assertions) throws Exception {
        for (boolean compact : new boolean[]{false, true}) {
            run(() -> {
                tomato.gui.modern.ContentStyle.refreshFonts(window);
                window.setSize(compact ? COMPACT_WIDTH : wideWidth, compact ? COMPACT_HEIGHT : wideHeight);
                if (!window.isVisible()) window.setVisible(true);
                UiTestLayout.settle(window);
            });
            for (int pass = 0; pass < 3; pass++) SwingUtilities.invokeAndWait(() -> UiTestLayout.settle(window));
            run(() -> {
                if (assertions != null) assertions.run();
                capture(window, name + (compact ? "-compact" : "-wide"));
            });
        }
    }

    /** Paints the realized window at its device scale and writes a PNG; asserts it is not a blank fill. */
    public static File capture(Window window, String name) {
        assertTrue(SwingUtilities.isEventDispatchThread());
        assertNotNull("Evidence window for " + name, window);
        UiTestLayout.settle(window);
        AffineTransform device = window.getGraphicsConfiguration().getDefaultTransform();
        double sx = Math.max(1, device.getScaleX()), sy = Math.max(1, device.getScaleY());
        int width = (int) Math.ceil(window.getWidth() * sx), height = (int) Math.ceil(window.getHeight() * sy);
        assertTrue("Allocated window for " + name, width > 0 && height > 0);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.scale(sx, sy); window.printAll(graphics); graphics.dispose();
        assertTrue("Rendered content, not a blank fill: " + name, distinctColors(image, 12) >= 12);
        File directory = new File("screenshots", FOLDER);
        assertTrue("Evidence directory", directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, name + ".png");
        try { assertTrue("PNG writer", ImageIO.write(image, "png", file)); }
        catch (Exception e) { throw new AssertionError(e); }
        System.out.println("wave3 evidence " + file.getPath() + " window=" + window.getSize() + " scale=" + sx + " image=" + width + "x" + height);
        return file;
    }

    private static int distinctColors(BufferedImage image, int enough) {
        Set<Integer> colors = new HashSet<>();
        int stepX = Math.max(1, image.getWidth() / 200), stepY = Math.max(1, image.getHeight() / 200);
        for (int y = 0; y < image.getHeight(); y += stepY) for (int x = 0; x < image.getWidth(); x += stepX) {
            colors.add(image.getRGB(x, y)); if (colors.size() >= enough) return colors.size();
        }
        return colors.size();
    }

    /** True when a showing text component, label, button or table cell under {@code root} contains {@code text}. */
    public static boolean shows(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JTextComponent && ((JTextComponent) child).getText().contains(text)) return true;
            if (child instanceof JLabel && ((JLabel) child).getText() != null && ((JLabel) child).getText().contains(text)) return true;
            if (child instanceof AbstractButton && ((AbstractButton) child).getText() != null && ((AbstractButton) child).getText().contains(text)) return true;
            if (child instanceof JTable) {
                TableModel model = ((JTable) child).getModel();
                for (int r = 0; r < model.getRowCount(); r++) for (int c = 0; c < model.getColumnCount(); c++)
                    if (String.valueOf(model.getValueAt(r, c)).contains(text)) return true;
            }
            if (child instanceof JTabbedPane) {
                JTabbedPane tabs = (JTabbedPane) child;
                for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getTitleAt(i).contains(text)) return true;
            }
            if (child instanceof Container && shows((Container) child, text)) return true;
        }
        return false;
    }

    public static void assertShows(Container root, String... texts) {
        for (String text : texts) assertTrue("Expected visible text: " + text, shows(root, text));
    }

    /**
     * Records (without failing) whether a wrapped text area received its full preferred height; a shortfall is printed
     * as a "wave3 layout finding" for the visual reviewer instead of being hidden by an enlarged window.
     */
    public static boolean measureText(JTextArea area) {
        int needed = area.getUI().getPreferredSize(area).height;
        boolean complete = area.getHeight() >= needed;
        if (!complete) System.out.println("wave3 layout finding: " + area.getName() + " allocated=" + area.getSize() + " needed height=" + needed
            + " window=" + SwingUtilities.getWindowAncestor(area).getSize());
        return complete;
    }

    /** Scrolls every enclosing viewport so the top of {@code component} (up to {@code height} px) is on screen. */
    public static void reveal(JComponent component, int height) {
        assertTrue(SwingUtilities.isEventDispatchThread());
        UiTestLayout.settle(SwingUtilities.getWindowAncestor(component));
        Rectangle region = new Rectangle(0, 0, Math.max(1, component.getWidth()), Math.max(1, Math.min(height, component.getHeight())));
        // A JViewport does not forward scrollRectToVisible, so scroll each enclosing viewport, innermost first.
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof JViewport && ((JViewport) parent).getView() instanceof JComponent) {
                JComponent view = (JComponent) ((JViewport) parent).getView();
                view.scrollRectToVisible(SwingUtilities.convertRectangle(component, region, view));
            }
        }
        if (!component.isShowing() || component.getVisibleRect().isEmpty()) {
            StringBuilder chain = new StringBuilder();
            for (Container c = component; c != null; c = c.getParent()) chain.append(" <- ").append(c.getClass().getSimpleName()).append(' ').append(c.getName()).append(' ').append(c.getBounds()).append(" visible=").append(c.isVisible());
            fail("Revealed component is showing: " + component.getName() + " visibleRect=" + component.getVisibleRect() + chain);
        }
    }

    public static void reveal(JComponent component) { reveal(component, 160); }

    /** Scrolls so {@code row} of {@code table} is visible. */
    public static void revealRow(JTable table, int row) {
        Rectangle cell = table.getCellRect(row, 0, true);
        table.scrollRectToVisible(new Rectangle(0, cell.y, 1, cell.height));
    }

    public static <T extends Component> T named(Container root, String name, Class<T> type) {
        return VisualEvidence.named(root, name, type);
    }

    /** Selects the tab titled {@code title} in the first tabbed pane under {@code root} that has it. */
    public static void selectTab(Container root, String title) {
        JTabbedPane tabs = VisualEvidence.find(root, JTabbedPane.class, pane -> pane.indexOfTab(title) >= 0);
        tabs.setSelectedIndex(tabs.indexOfTab(title));
    }

    public static Window window(Class<? extends Window> type, String title) {
        for (Window window : Window.getWindows())
            if (type.isInstance(window) && window.isShowing() && (title == null || title.equals(windowTitle(window)))) return window;
        return null;
    }

    private static String windowTitle(Window window) {
        return window instanceof Dialog ? ((Dialog) window).getTitle() : window instanceof Frame ? ((Frame) window).getTitle() : null;
    }

}
