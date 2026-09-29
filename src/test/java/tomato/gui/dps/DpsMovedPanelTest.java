package tomato.gui.dps;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import static org.junit.Assert.*;

/** Page 7 after P5b: the DPS Logger page only points at Runs & DPS, where the live meter and the recordings now live. */
public class DpsMovedPanelTest {
    @Test public void pointsAtTheLiveMeterAndRecordingsTabsOfRunsAndDps() throws Exception {
        AtomicInteger liveMeter = new AtomicInteger(), recordings = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            DpsMovedPanel moved = new DpsMovedPanel(liveMeter::incrementAndGet, recordings::incrementAndGet);
            assertTrue(moved instanceof JPanel);
            assertEquals("dps-moved", moved.getName());
            EmptyState empty = named(moved, "empty-state", EmptyState.class);
            assertNotNull("The pointer is an EmptyState, as Build moved is", empty);
            assertEquals("DPS Logger moved", empty.getAccessibleContext().getAccessibleName());
            assertEquals("The live meter and your recordings are now tabs of Runs & DPS.", empty.getAccessibleContext().getAccessibleDescription());

            KitButton open = named(moved, "dps-moved-open", KitButton.class), library = named(moved, "dps-moved-recordings", KitButton.class);
            assertNotNull(open);
            assertNotNull(library);
            assertEquals("Open Live meter", open.getText());
            assertEquals(KitButton.Variant.PRIMARY, open.variant());
            assertEquals("Open Recordings", library.getText());
            assertEquals(KitButton.Variant.SECONDARY, library.variant());
            assertTrue("Both destinations always exist, so both buttons stay enabled", open.isEnabled() && library.isEnabled());
            assertSame("Both actions sit inside the empty state", empty, SwingUtilities.getAncestorOfClass(EmptyState.class, open));
            assertSame(empty, SwingUtilities.getAncestorOfClass(EmptyState.class, library));
            assertNotNull(open.getToolTipText());
            assertNotNull(library.getToolTipText());

            open.doClick();
            assertEquals("Open Live meter runs its action once", 1, liveMeter.get());
            assertEquals("…and not the other", 0, recordings.get());
            library.doClick();
            assertEquals(1, recordings.get());
            assertEquals(1, liveMeter.get());

            moved.setSize(660, 480);   // the compact shell's content area
            layoutTree(moved);
            for (KitButton button : new KitButton[] {open, library}) {
                Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), moved);
                assertTrue(button.getName() + " is whole: " + bounds, button.getWidth() >= button.getPreferredSize().width
                    && new Rectangle(moved.getSize()).contains(bounds));
            }
            assertEquals("Side by side, primary first", open.getY(), library.getY());
            assertTrue(open.getX() < library.getX());
        });
    }

    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container) layoutTree((Container) child);
    }

    @Test public void refusesMissingActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { new DpsMovedPanel(null, () -> {}); fail(); } catch (NullPointerException expected) { }
            try { new DpsMovedPanel(() -> {}, null); fail(); } catch (NullPointerException expected) { }
        });
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
