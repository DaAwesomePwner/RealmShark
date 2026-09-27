package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** KitLayouts: full-width stacks that skip hidden rows; spread rows that keep one line when they fit and wrap below the lead otherwise. */
public class KitLayoutsTest {
    private static JComponent box(int width, int height) { JPanel box = new JPanel(); box.setPreferredSize(new Dimension(width, height)); return box; }
    /** Sizes a panel to its preferred height at this width, twice (spread rows measure against their width). */
    private static void lay(JComponent panel, int width) { for (int pass = 0; pass < 2; pass++) { panel.setSize(width, panel.getPreferredSize().height); panel.doLayout(); } }

    @Test public void stackFillsTheWidthInOrderAndHiddenRowsTakeNoSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent first = box(50, 20), hidden = box(50, 30), last = box(80, 10);
            hidden.setVisible(false);
            JPanel stack = KitLayouts.stack(6, first, hidden, last);
            assertFalse(stack.isOpaque());
            assertEquals("20 + gap + 10: the hidden row and its gap are skipped", 36, stack.getPreferredSize().height);
            lay(stack, 300);
            assertEquals(new Rectangle(0, 0, 300, 20), first.getBounds()); assertEquals(new Rectangle(0, 26, 300, 10), last.getBounds());
        });
    }

    @Test public void spreadKeepsOneRowWhenItFitsAndWrapsBelowTheLeadOtherwise() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent lead = box(100, 20), a = box(60, 30), b = box(60, 10);
            JPanel row = KitLayouts.spread(8, lead, a, b);
            lay(row, 400);
            assertEquals("One row, as tall as its tallest part", 30, row.getPreferredSize().height);
            assertEquals("The lead stretches to the trailing parts", new Rectangle(0, 5, 264, 20), lead.getBounds());
            assertEquals(new Rectangle(272, 0, 60, 30), a.getBounds()); assertEquals(new Rectangle(340, 10, 60, 10), b.getBounds());
            lay(row, 200);
            assertEquals("The lead's line, then the trailing parts", 58, row.getPreferredSize().height);
            assertEquals(new Rectangle(0, 0, 200, 20), lead.getBounds());
            assertEquals(new Rectangle(0, 28, 60, 30), a.getBounds()); assertEquals(new Rectangle(68, 38, 60, 10), b.getBounds());
            a.setVisible(false); b.setVisible(false);
            lay(row, 200);
            assertEquals("Hidden parts take no space", 20, row.getPreferredSize().height);
        });
    }

    @Test public void spreadWhenWideWrapsWhileTheWindowIsNarrow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel narrow = KitLayouts.spreadWhenWide(1000, 8, box(100, 20), box(60, 20));
            lay(narrow, 400);
            assertEquals("Below 1000 px the row wraps although it would fit", 48, narrow.getPreferredSize().height);
            JPanel wide = KitLayouts.spreadWhenWide(300, 8, box(100, 20), box(60, 20));
            lay(wide, 400);
            assertEquals(20, wide.getPreferredSize().height);
            assertEquals("Outside a window, the component's own width", 400, KitLayouts.rootWidth(wide));
            try { KitLayouts.spread(8); fail("A spread row needs a lead"); } catch (IllegalArgumentException expected) {}
        });
    }
}
