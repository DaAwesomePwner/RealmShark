package tomato.gui.kit;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

/**
 * The light theme's raised tiles sit about 1.03:1 against the canvas, so Tokens.outline gives them a 1 px edge there
 * (BORDER_SUBTLE, BORDER under Increase contrast). Dark pixels never move.
 */
public class LightOutlineTest {
    private static final int WIDTH = 160, HEIGHT = 72;

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void aLightTileHasAnOutlineAtItsEdgeThatDiffersFromTheCanvasAndTheFill() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            Color canvas = Tokens.color(Tokens.Role.CANVAS), fill = Tokens.color(Tokens.Role.RAISED), edge = Tokens.color(Tokens.Role.BORDER_SUBTLE);
            BufferedImage outlined = paint(tile()), plain = paint(plainTile());
            for (Point point : edges()) {
                int rgb = outlined.getRGB(point.x, point.y);
                assertNotEquals("Not the canvas at " + point, canvas.getRGB(), rgb);
                assertNotEquals("Not the fill at " + point, fill.getRGB(), rgb);
                assertEquals("The subtle border at " + point, edge.getRGB(), rgb);
                assertNotEquals("Without the outline there is no edge, which is what made tiles vanish", edge.getRGB(), plain.getRGB(point.x, point.y));
            }
            assertEquals("The inside keeps the raised fill", fill.getRGB(), outlined.getRGB(4, HEIGHT - 4));
        });
    }

    @Test public void aDarkTileIsPixelIdenticalToOneWithoutTheOutline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertTrue("Dark captures do not move", same(paint(tile()), paint(plainTile())));
            Themes.install(new Themes.Choice(Themes.Variant.DARK, true));
            assertTrue("…with Increase contrast too", same(paint(tile()), paint(plainTile())));
        });
    }

    @Test public void increaseContrastOutlinesWithTheStrongerBorder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, true));
            Color border = Tokens.color(Tokens.Role.BORDER);
            assertNotEquals(border, Tokens.color(Tokens.Role.BORDER_SUBTLE));
            BufferedImage outlined = paint(tile());
            for (Point point : edges()) assertEquals("BORDER at " + point, border.getRGB(), outlined.getRGB(point.x, point.y));
        });
    }

    @Test public void theOutlineHelperDrawsNothingInTheDarkTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean contrast : new boolean[]{false, true}) {
                Themes.install(new Themes.Choice(Themes.Variant.DARK, contrast));
                BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                try { Tokens.outline(g, new RoundRectangle2D.Float(0, 0, 39, 39, Tokens.ARC_CARD, Tokens.ARC_CARD)); } finally { g.dispose(); }
                for (int x = 0; x < 40; x++) for (int y = 0; y < 40; y++) assertEquals(0, image.getRGB(x, y));
            }
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.MAGENTA);
            try { Tokens.outline(g, new Rectangle(0, 0, 39, 39)); assertEquals("The caller's color is kept", Color.MAGENTA, g.getColor()); } finally { g.dispose(); }
            assertEquals(Tokens.color(Tokens.Role.BORDER_SUBTLE).getRGB(), image.getRGB(0, 20));
            assertEquals("A 1 px line", 0, image.getRGB(1, 20));
        });
    }

    private static StatTile tile() {
        StatTile tile = new StatTile("Runs today");
        tile.setValue(DisplayValue.known("7", "Saved runs"), "5 completed");
        return tile;
    }

    /** The tile as painted before the outline: the raised fill only. */
    private static StatTile plainTile() {
        StatTile tile = new StatTile("Runs today") {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Tokens.color(Tokens.Role.RAISED));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
                g.dispose();
            }
        };
        tile.setValue(DisplayValue.known("7", "Saved runs"), "5 completed");
        return tile;
    }

    /** Midpoints of the four straight edges, away from the rounded corners (the outline is Card's: 0 to width - 1). */
    private static Point[] edges() {
        return new Point[]{new Point(0, HEIGHT / 2), new Point(WIDTH / 2, 0), new Point(WIDTH - 1, HEIGHT / 2), new Point(WIDTH / 2, HEIGHT - 1)};
    }

    /** Paints the tile on the canvas color, as its page would. */
    private static BufferedImage paint(StatTile tile) {
        JPanel page = new JPanel(null);
        page.setBackground(Tokens.color(Tokens.Role.CANVAS));
        page.setSize(WIDTH + 8, HEIGHT + 8);
        page.add(tile);
        tile.setBounds(0, 0, WIDTH, HEIGHT);
        tile.doLayout();
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Tokens.color(Tokens.Role.CANVAS));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            tile.paint(g);
        } finally { g.dispose(); }
        return image;
    }

    private static boolean same(BufferedImage a, BufferedImage b) {
        for (int x = 0; x < a.getWidth(); x++) for (int y = 0; y < a.getHeight(); y++) if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
        return true;
    }
}
