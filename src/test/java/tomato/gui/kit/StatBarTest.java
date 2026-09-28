package tomato.gui.kit;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The stat bar's fill: GOOD when maxed; while short, WARN by default (a stat below its cap needs potions) or the in-progress role
 * a caller sets (a pet ability below its maximum is progress, not a warning); an empty track when unknown.
 */
public class StatBarTest {
    /** Paints a 100×6 bar and returns the color at {@code x}, the middle row. */
    private static Color pixel(StatBar bar, int x) {
        bar.setSize(100, 6);
        BufferedImage image = new BufferedImage(100, 6, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        bar.paint(g);
        g.dispose();
        return new Color(image.getRGB(x, 3), true);
    }

    @Test public void aShortBarIsWarnByDefaultOrTheProgressRoleItIsGiven() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatBar bar = new StatBar();
            assertEquals("Every existing stat bar keeps its amber", Tokens.Role.WARN, bar.progressRole());
            bar.set(50, 100);
            assertEquals(Tokens.color(Tokens.Role.WARN), pixel(bar, 25));
            assertEquals("The unfilled track", Tokens.color(Tokens.Role.CONTROL), pixel(bar, 75));
            bar.setProgressRole(Tokens.Role.ACCENT);
            assertEquals(Tokens.Role.ACCENT, bar.progressRole());
            assertEquals("In progress: the role set", Tokens.color(Tokens.Role.ACCENT), pixel(bar, 25));
            bar.set(100, 100);
            assertTrue(bar.maxed());
            assertEquals("Maxed stays GOOD whatever the progress role", Tokens.color(Tokens.Role.GOOD), pixel(bar, 50));
            bar.set(null, 100);
            assertEquals("Unknown: an empty track, never a filled 0", Tokens.color(Tokens.Role.CONTROL), pixel(bar, 25));
            assertEquals("Not captured", bar.getToolTipText());
        });
    }

    @Test(expected = NullPointerException.class) public void theProgressRoleIsRequired() {
        new StatBar().setProgressRole(null);
    }
}
