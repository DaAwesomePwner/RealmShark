package tomato.gui.kit;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import javax.accessibility.AccessibleRole;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The stacked bar of the quest Planner (spec §6.5): reserved ACCENT, covered GOOD and missing WARN over a CONTROL track, sized by
 * their share of the total (long values: a combined demand can exceed int); an unknown total draws nothing that could read as 0%.
 */
public class SegmentBarTest {
    private Locale format;

    @Before public void usFormat() { format = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, format); }

    /** Paints a 100×6 bar and returns the color at {@code x}, the middle row. */
    private static Color pixel(SegmentBar bar, int x) {
        bar.setSize(100, 6);
        BufferedImage image = new BufferedImage(100, 6, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        bar.paint(g);
        g.dispose();
        return new Color(image.getRGB(x, 3), true);
    }

    @Test public void segmentsStackInOrderAndSumToTheirShareOfTheTotal() throws Exception {
        assertArrayEquals("Right edges: reserved, then covered, then missing", new int[] {20, 50, 100}, SegmentBar.edges(2, 3, 5, 10, 100));
        assertArrayEquals("Short of the total: the rest is track", new int[] {10, 20, 30}, SegmentBar.edges(1, 1, 1, 6, 60));
        assertArrayEquals("Beyond int (a combined demand)", new int[] {60, 80, 100},
            SegmentBar.edges(3_000_000_000L, 1_000_000_000L, 1_000_000_000L, 5_000_000_000L, 100));
        assertArrayEquals("Segments above the total never overflow the bar", new int[] {50, 100, 100}, SegmentBar.edges(2, 2, 0, 3, 100));
        assertArrayEquals("A zero total fills nothing", new int[] {0, 0, 0}, SegmentBar.edges(0, 0, 0, 0, 100));
        SwingUtilities.invokeAndWait(() -> {
            SegmentBar bar = new SegmentBar();
            bar.set(2L, 3L, 5L, 10L);
            assertTrue(bar.known());
            assertEquals("Reserved", Tokens.color(Tokens.Role.ACCENT), pixel(bar, 10));
            assertEquals("Covered", Tokens.color(Tokens.Role.GOOD), pixel(bar, 35));
            assertEquals("Missing", Tokens.color(Tokens.Role.WARN), pixel(bar, 75));
            bar.set(1L, 1L, 1L, 6L);
            assertEquals("The unfilled rest of the total is track", Tokens.color(Tokens.Role.CONTROL), pixel(bar, 75));
        });
    }

    @Test public void anUnknownTotalDrawsNoTrackAtAll() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SegmentBar bar = new SegmentBar();
            assertFalse("A new bar is unknown", bar.known());
            assertEquals("Unknown: nothing painted, never an empty track that reads as 0%", 0, pixel(bar, 50).getAlpha());
            assertEquals("Not captured", bar.getToolTipText());
            assertEquals("Not captured", bar.getAccessibleContext().getAccessibleDescription());
            bar.set(2L, 3L, 5L, 10L);
            bar.set(1L, 0L, 0L, null);
            assertFalse("A null total is unknown whatever the segments", bar.known());
            assertEquals(0, pixel(bar, 5).getAlpha());
            bar.set(null, 0L, 0L, 4L);
            assertFalse("A null segment is unknown too", bar.known());
        });
    }

    @Test public void itIsAProgressBarDescribingEverySegment() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SegmentBar bar = new SegmentBar();
            assertEquals(AccessibleRole.PROGRESS_BAR, bar.getAccessibleContext().getAccessibleRole());
            bar.set(2L, 3L, 5L, 10L);
            assertEquals("Reserved 2, covered 3, missing 5 of 10", bar.getAccessibleContext().getAccessibleDescription());
            assertEquals("Reserved 2, covered 3, missing 5 of 10", bar.getToolTipText());
            bar.set(3_000_000_000L, 1_000_000_000L, 1_000_000_000L, 5_000_000_000L);
            assertEquals("Reserved 3,000,000,000, covered 1,000,000,000, missing 1,000,000,000 of 5,000,000,000",
                bar.getAccessibleContext().getAccessibleDescription());
        });
    }

    @Test(expected = IllegalArgumentException.class) public void negativeSegmentsAreRejected() {
        new SegmentBar().set(-1L, 0L, 0L, 1L);
    }
}
