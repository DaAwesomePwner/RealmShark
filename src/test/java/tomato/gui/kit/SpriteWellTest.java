package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

/** The bag sprite well shared by run cards and Loot Highlights: tinted and outlined in the bag's color, the sprite centered and fitted. */
public class SpriteWellTest {
    private static final int X = 4, Y = 6, SIDE = 26, MIDDLE = Y + SIDE / 2;

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void aWellIsTintedAndOutlinedInItsBagColorWithTheSpriteCenteredAndFittedNeverEnlarged() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                Color bag = Tokens.bag("Orange"), tint = Tokens.tint(bag);
                // A 40 px sprite in a 26 px well is scaled down to the well's room (26 - WELL = 20 px), centered: x 7..26.
                BufferedImage large = well(new Solid(40), "B.Orange");
                assertEquals(variant + ": outlined in the bag's color (a boosted bag shares it)", bag.getRGB(), large.getRGB(X, MIDDLE));
                assertEquals(variant + ": tinted inside", tint.getRGB(), large.getRGB(X + 2, MIDDLE));
                assertEquals(Solid.INK.getRGB(), large.getRGB(X + 3, MIDDLE));
                assertEquals(Solid.INK.getRGB(), large.getRGB(X + SIDE - Sprites.WELL / 2 - 1, MIDDLE));
                assertEquals("The sprite fits the well's room", tint.getRGB(), large.getRGB(X + SIDE - Sprites.WELL / 2, MIDDLE));
                assertEquals("Nothing is painted outside the well", 0, large.getRGB(X + SIDE, MIDDLE) >>> 24);
                // A 10 px sprite keeps its size, centered: x 12..21.
                BufferedImage small = well(new Solid(10), "B.Orange");
                assertEquals(tint.getRGB(), small.getRGB(X + 7, MIDDLE));
                assertEquals(Solid.INK.getRGB(), small.getRGB(X + 8, MIDDLE));
                assertEquals(Solid.INK.getRGB(), small.getRGB(X + 17, MIDDLE));
                assertEquals("Never enlarged", tint.getRGB(), small.getRGB(X + 18, MIDDLE));
                // A bag that was not saved is muted, never mistaken for a known bag.
                assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED).getRGB(), well(new Solid(10), null).getRGB(X, MIDDLE));
            }
        });
    }

    private static BufferedImage well(Icon sprite, String bag) {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { Sprites.paintWell(new JLabel(), g, sprite, bag, X, Y, SIDE); } finally { g.dispose(); }
        return image;
    }

    private static final class Solid implements Icon {
        static final Color INK = new Color(200, 40, 90);
        private final int size;
        Solid(int size) { this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { g.setColor(INK); g.fillRect(x, y, size, size); }
    }
}
