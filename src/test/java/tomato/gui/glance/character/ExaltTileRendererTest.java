package tomato.gui.glance.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import static org.junit.Assert.*;

/** The painted exalt tile: its text, an unknown loot boost never shown as zero, the heat strip's colors and the accessible name. */
public class ExaltTileRendererTest {
    private Locale format;
    private Font font;

    @Before public void usFormatAndFont13() throws Exception {
        format = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        SwingUtilities.invokeAndWait(() -> { font = ContentStyle.body(); ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13)); });
    }
    @After public void restore() throws Exception {
        Locale.setDefault(Locale.Category.FORMAT, format);
        SwingUtilities.invokeAndWait(() -> ContentStyle.setBodyFont(font));
    }

    private static AccountExalts.Tile tile(int total, int lowest, Integer boost) {
        return new AccountExalts.Tile(ExaltFixtures.WIZARD, "Wizard", total, lowest, List.of(3, 4, 3, 5, 3, 3, 4, 3), boost, 0);
    }

    @Test public void aTileShowsClassTotalLowestTierHeatStripAndLootBoost() {
        ExaltTileRenderer.Lines lines = ExaltTileRenderer.lines(tile(1_312, 3, 15));
        assertEquals("Wizard", lines.title());
        assertEquals("Total 1,312", lines.total());
        assertEquals(3, lines.lowest());
        assertEquals("Lowest 3/5", lines.tier());
        assertEquals("One heat cell per stat, canonical order", List.of(3, 4, 3, 5, 3, 3, 4, 3), lines.heat());
        assertEquals("+15% loot", lines.loot());
        assertEquals("A known zero is a boost of 0, not unknown", "+0% loot", ExaltTileRenderer.lines(tile(5, 0, 0)).loot());
    }

    @Test public void anUnknownBoostShowsADashNeverZero() {
        ExaltTileRenderer.Lines lines = ExaltTileRenderer.lines(tile(312, 3, null));
        assertEquals("Loot —", lines.loot());
        assertEquals("Wizard: 312 completions, lowest tier 3 of 5, loot boost unknown", ExaltTileRenderer.accessibleName(tile(312, 3, null)));
    }

    @Test public void theAccessibleNameSaysCompletionsLowestTierAndBoost() {
        assertEquals("Wizard: 312 completions, lowest tier 3 of 5, loot boost 15%", ExaltTileRenderer.accessibleName(tile(312, 3, 15)));
        assertEquals("Wizard: 1 completion, lowest tier 0 of 5, loot boost 0%", ExaltTileRenderer.accessibleName(tile(1, 0, 0)));
    }

    @Test public void heatCellsRunFromControlTowardAccentAndMaxedIsGood() {
        assertEquals(Tokens.color(Tokens.Role.CONTROL), ExaltTileRenderer.heat(0));
        assertEquals(Tokens.color(Tokens.Role.GOOD), ExaltTileRenderer.heat(5));
        Color accent = Tokens.color(Tokens.Role.ACCENT);
        for (int tier = 1; tier < 4; tier++)
            assertTrue("Tier " + (tier + 1) + " is nearer the accent than tier " + tier,
                distance(ExaltTileRenderer.heat(tier + 1), accent) < distance(ExaltTileRenderer.heat(tier), accent));
        assertNotEquals("Tier 1 is visibly more than none", ExaltTileRenderer.heat(0), ExaltTileRenderer.heat(1));
    }

    @Test public void oneComponentServesEveryCellPaintsTheTileAndGrowsWithTheFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ExaltTileRenderer renderer = new ExaltTileRenderer();
            JList<AccountExalts.Tile> list = new JList<>();
            Component first = renderer.getListCellRendererComponent(list, tile(312, 3, 15), 0, true, true);
            Component second = renderer.getListCellRendererComponent(list, tile(40, 0, null), 1, false, false);
            assertSame("One reusable component, no per-tile trees (spec §9)", first, second);
            assertEquals("Wizard: 40 completions, lowest tier 0 of 5, loot boost unknown", renderer.getAccessibleContext().getAccessibleName());
            assertTrue(renderer.getToolTipText(), renderer.getToolTipText().contains("Loot boost unknown"));
            assertTrue("The tooltip names every stat's tier", renderer.getToolTipText().contains("Life 3/5") && renderer.getToolTipText().contains("WIS 3/5"));
            assertEquals("The cell is the renderer's preferred size (TileList)", renderer.cellSize(), renderer.getPreferredSize());
            Dimension small = renderer.cellSize();
            renderer.setSize(small);
            BufferedImage image = new BufferedImage(small.width, small.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            renderer.paint(g);
            g.dispose();
            assertTrue("The tile is painted", painted(image));
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 18));
            Dimension large = renderer.cellSize();
            assertTrue("Tiles grow with the body font: " + small + " -> " + large, large.width > small.width && large.height > small.height);
        });
    }

    /** A tile's counts are saved ones: its tooltip names that source and when they last changed (spec §1: stale is labeled). */
    @Test public void theTooltipNamesTheSavedCountsAndWhenTheyChanged() throws Exception {
        long seen = System.currentTimeMillis() - 2 * 3_600_000L; // KitFormat.relative reads the real clock
        SwingUtilities.invokeAndWait(() -> {
            ExaltTileRenderer renderer = new ExaltTileRenderer();
            JList<AccountExalts.Tile> list = new JList<>();
            renderer.getListCellRendererComponent(list, new AccountExalts.Tile(ExaltFixtures.WIZARD, "Wizard", 312, 3, List.of(3, 4, 3, 5, 3, 3, 4, 3), 15, seen),
                0, false, false);
            assertTrue(renderer.getToolTipText(), renderer.getToolTipText().endsWith("Loot boost +15% · From saved exalt counts, changed 2 h ago"));
            renderer.getListCellRendererComponent(list, tile(312, 3, null), 0, false, false);
            assertTrue("An unknown time says so", renderer.getToolTipText().endsWith(" · From saved exalt counts, changed at an unknown time"));
        });
    }

    private static double distance(Color a, Color b) {
        int r = a.getRed() - b.getRed(), g = a.getGreen() - b.getGreen(), bl = a.getBlue() - b.getBlue();
        return Math.sqrt(r * r + g * g + bl * bl);
    }

    private static boolean painted(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) return true;
        return false;
    }
}
