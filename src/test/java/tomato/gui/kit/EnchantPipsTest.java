package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;

/** Shared corners preserve slot counts and tier legibility; halos preserve sprites and follow the theme without tinting placeholders. */
public class EnchantPipsTest {
    @Test public void pipsCountSlotsAndStayInsideTheBottomEdgeInEveryTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (Themes.Variant variant : Themes.Variant.values()) {
                    Themes.install(new Themes.Choice(variant, false));
                    for (int side : new int[] {15, 19, 23, 25, 29, 37, 39, 47, 53}) for (int count = 1; count <= 4; count++) {
                        EnchantInfo info = EnchantInfo.ofSlotCount(count);
                        BufferedImage image = pips(info, side);
                        int d = EnchantPips.size(side, count), gap = EnchantPips.gap(side, count);
                        int width = count * d + (count - 1) * gap;
                        boolean clippedBacking = side == 15 && count == 4;
                        int right = clippedBacking ? side : side - 2, top = side - 1 - d;
                        int ink = Tokens.rarity(info.rarity()).getRGB();
                        int runs = 0, rightmost = -1; boolean previous = false;
                        for (int x = 0; x <= side; x++) {
                            boolean colored = false;
                            for (int y = 0; y <= side; y++) {
                                int pixel = image.getRGB(x, y);
                                if ((pixel >>> 24) != 0) {
                                    assertTrue("Inside the edge and in the bottom half",
                                        (clippedBacking ? x >= 0 && x <= side : x > 0 && x < side) && y > side / 2 && y < side);
                                    rightmost = Math.max(rightmost, x);
                                }
                                colored |= pixel == ink;
                            }
                            if (colored && !previous) runs++;
                            previous = colored;
                        }
                        assertEquals(variant + " side=" + side, count, runs);
                        assertEquals("Backing reaches one pixel beyond the solid, clipped only for the cramped Bridge row", clippedBacking ? side : side - 1, rightmost);
                        assertTrue("Solid diamonds fit without clipping", right - width + 1 >= 0 && right <= side);
                        assertTrue("At least one pixel separates solids", gap >= 1);
                        if (count * d + (count - 1) * 2 <= side - 3) assertTrue("Use two pixels when backing fits", gap >= 2);
                        assertTrue("Bridge diamonds stay at least three pixels", d >= 3);
                        if (side >= 23) assertTrue("Table and slot pips stay legible", d >= 4);
                        if (side == 39 && count == 4) assertEquals("Card pips stay six pixels", 6, d);
                        int cy = top + d / 2;
                        for (int i = 0; i < count; i++) {
                            int left = right - d + 1 - i * (d + gap), cx = left + d / 2;
                            for (int[] offset : new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}})
                                assertEquals(variant + " side=" + side + " pip=" + i + ": center and four neighbours are solid",
                                    ink, image.getRGB(cx + offset[0], cy + offset[1]));
                            int area = 0;
                            for (int py = top; py < top + d; py++) for (int px = left; px < left + d; px++)
                                if (image.getRGB(px, py) == ink) area++;
                            assertEquals("The backing never erases the diamond's solid area", d % 2 == 0 ? d * d / 2 + d : (d * d + 1) / 2, area);
                            if (i == count - 1) continue;
                            for (int between = 1; between <= gap; between++) {
                                int blank = left - between;
                                for (int py = 0; py <= side; py++) assertNotEquals("Distinct colored diamonds never merge", ink, image.getRGB(blank, py));
                                assertEquals("Backing separates diamonds at their widest row", Tokens.color(Tokens.Role.CANVAS).getRGB(), image.getRGB(blank, cy));
                                assertEquals("A clear notch keeps the backings from forming a rectangular blob", 0, image.getRGB(blank, top));
                            }
                        }
                    }
                }
            } finally { Themes.install(new Themes.Choice(Themes.Variant.DARK, false)); }
        });
    }

    @Test public void absentAndUnknownDataPaintNothingWhileUnreadableHasOneMutedPip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (EnchantInfo info : new EnchantInfo[] {null, EnchantInfo.notRecorded(), EnchantInfo.ofSlotCount(0),
                    new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNKNOWN, List.of())}) {
                BufferedImage image = pips(info, 25);
                for (int pixel : image.getRGB(0, 0, 26, 26, null, 0, 26)) assertEquals(0, pixel);
            }
            BufferedImage unreadable = pips(EnchantInfo.unreadable(), 25);
            int d = EnchantPips.size(25, 1), center = 25 - 1 - d + d / 2;
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED).getRGB(), unreadable.getRGB(center, center));
            assertEquals(0, unreadable.getRGB(center - d - EnchantPips.gap(25, 1), center));
        });
    }

    @Test public void bridgeRowClipsOnlyBackingAtTheIconEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try { EnchantPips.paint(g, EnchantInfo.ofSlotCount(4), 4, 4, 15); } finally { g.dispose(); }
            int ink = Tokens.rarity(EnchantInfo.Rarity.DIVINE).getRGB(), colored = 0;
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                if (x < 4 || x > 19 || y < 4 || y > 19) assertEquals("No backing escapes the icon bounds", 0, image.getRGB(x, y));
                if (image.getRGB(x, y) == ink) colored++;
            }
            assertEquals("All four complete three-pixel diamonds survive clipping", 4 * 5, colored);
            assertEquals("The rightmost solid diamond reaches the icon edge", ink, image.getRGB(19, 16));
        });
    }

    @Test public void glowAddsRarityAroundOpaquePixelsWithoutChangingTheSprite() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon sprite = new Icon() {
                @Override public int getIconWidth() { return 20; }
                @Override public int getIconHeight() { return 20; }
                @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                    g.setColor(Color.WHITE); g.fillRect(x + 8, y + 8, 4, 4);
                }
            };
            BufferedImage plain = icon(sprite);
            try {
                Icon retained = EnchantPips.glow(sprite, EnchantInfo.ofSlotCount(3));
                for (Themes.Variant variant : Themes.Variant.values()) {
                    Themes.install(new Themes.Choice(variant, false));
                    for (int count = 1; count <= 4; count++) {
                        EnchantInfo info = EnchantInfo.ofSlotCount(count);
                        BufferedImage glowing = icon(count == 3 ? retained : EnchantPips.glow(sprite, info));
                        int added = 0;
                        for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) {
                            if (plain.getRGB(x, y) != 0) assertEquals(plain.getRGB(x, y), glowing.getRGB(x, y));
                            else if ((glowing.getRGB(x, y) >>> 24) != 0) {
                                added++;
                                Color expected = Tokens.rarity(info.rarity()), actual = new Color(glowing.getRGB(x, y), true);
                                assertTrue("Halo red follows rarity", Math.abs(expected.getRed() - actual.getRed()) <= 6);
                                assertTrue("Halo green follows rarity", Math.abs(expected.getGreen() - actual.getGreen()) <= 6);
                                assertTrue("Halo blue follows rarity", Math.abs(expected.getBlue() - actual.getBlue()) <= 6);
                                assertTrue((glowing.getRGB(x, y) >>> 24) < 255);
                            }
                        }
                        assertTrue("A halo hugs the sprite", added > 0);
                        assertEquals("Distant pixels stay clear", 0, glowing.getRGB(1, 1));
                    }
                }
                for (EnchantInfo info : new EnchantInfo[] {null, EnchantInfo.ofSlotCount(0), EnchantInfo.notRecorded(), EnchantInfo.unreadable()})
                    assertSame(sprite, EnchantPips.glow(sprite, info));
                Icon placeholder = Sprites.sprite(0, 20);
                assertArrayEquals(pixels(icon(placeholder)), pixels(icon(EnchantPips.glow(placeholder, EnchantInfo.ofSlotCount(4)))));
            } finally { Themes.install(new Themes.Choice(Themes.Variant.DARK, false)); }
        });
    }

    @Test public void wideLootIconsKeepPipsInTheirRightHandSquare() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon base = new ImageIcon(new BufferedImage(60, 21, BufferedImage.TYPE_INT_ARGB));
            for (Icon decorated : new Icon[] {new ItemIcon(base, "Bow", EnchantInfo.ofSlotCount(2), "UT"),
                    EnchantPips.decorate(base, EnchantInfo.ofSlotCount(2), "UT")}) {
                BufferedImage image = icon(decorated);
                int changed = 0;
                for (int y = 0; y < 21; y++) for (int x = 0; x < 60; x++) if (image.getRGB(x, y) != 0) {
                    changed++;
                    assertTrue(x >= 39 && x < 59 && y > 10 && y < 20);
                }
                assertTrue(changed > 0);
            }
        });
    }

    @Test public void eachRarityHasItsOwnColorAndDivineRemainsAmberInEveryTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (Themes.Variant variant : Themes.Variant.values()) {
                    Themes.install(new Themes.Choice(variant, false));
                    java.util.Set<Integer> inks = new java.util.HashSet<>();
                    for (int count = 1; count <= 4; count++) inks.add(EnchantPips.ink(EnchantInfo.ofSlotCount(count)).getRGB());
                    assertEquals(variant + ": four distinct rarity colors", 4, inks.size());
                    assertEquals(Tokens.color(Tokens.Role.WARN), Tokens.rarity(EnchantInfo.Rarity.DIVINE));
                    assertNull(Tokens.rarity(EnchantInfo.Rarity.UNENCHANTED));
                    assertNull(Tokens.rarity(EnchantInfo.Rarity.UNKNOWN));
                }
            } finally { Themes.install(new Themes.Choice(Themes.Variant.DARK, false)); }
        });
    }

    @Test public void tierLabelsUseTheirColorsInsideTheBottomRightStartingAtTwentyEightPixels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (Themes.Variant variant : Themes.Variant.values()) {
                    Themes.install(new Themes.Choice(variant, false));
                    for (int side : new int[] {27, 31, 39, 53}) for (String tier : new String[] {"UT", "ST", "T12"}) {
                        BufferedImage image = corner(null, tier, side);
                        int colored = 0, outline = 0, rightmost = -1, bottom = -1;
                        Color ink = Tokens.color(tier.equals("UT") ? Tokens.Role.WARN : tier.equals("ST") ? Tokens.Role.BAD : Tokens.Role.TEXT);
                        for (int y = 0; y <= side; y++) for (int x = 0; x <= side; x++) {
                            int pixel = image.getRGB(x, y);
                            if ((pixel >>> 24) == 0) continue;
                            assertTrue("Tier text stays inside the bottom-right region", x > 0 && x < side && y > side / 2 && y < side);
                            colored += pixel == ink.getRGB() ? 1 : 0;
                            outline += pixel == Tokens.color(Tokens.Role.CANVAS).getRGB() ? 1 : 0;
                            rightmost = Math.max(rightmost, x); bottom = Math.max(bottom, y);
                        }
                        assertTrue(variant + " " + tier + " paints its ink", colored > 0);
                        assertTrue("Dark outline supports the label", outline > 0);
                        assertTrue("Label is right anchored", rightmost >= side - 4);
                        assertTrue("Label is bottom anchored", bottom >= side - 6);
                    }
                }
            } finally { Themes.install(new Themes.Choice(Themes.Variant.DARK, false)); }
        });
    }

    @Test public void tierLabelsAreAbsentForSmallWellsAndEnchantedOrUntieredItems() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int side : new int[] {15, 23, 26}) for (int pixel : pixels(corner(null, "UT", side))) assertEquals(0, pixel);
            for (String tier : new String[] {null, ""}) for (int pixel : pixels(corner(null, tier, 39))) assertEquals(0, pixel);
            for (EnchantInfo info : new EnchantInfo[] {EnchantInfo.ofSlotCount(1), EnchantInfo.ofSlotCount(4), EnchantInfo.unreadable()})
                assertArrayEquals("Pips replace tier text", pixels(pips(info, 39)), pixels(corner(info, "UT", 39)));
            for (EnchantInfo info : new EnchantInfo[] {EnchantInfo.ofSlotCount(0), EnchantInfo.notRecorded(),
                    new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNKNOWN, List.of())})
                assertArrayEquals("Missing enchant evidence still allows tier text", pixels(corner(null, "UT", 39)), pixels(corner(info, "UT", 39)));
            Icon base = new ImageIcon(new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB));
            for (EnchantInfo info : new EnchantInfo[] {null, EnchantInfo.ofSlotCount(0), EnchantInfo.notRecorded(),
                    new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNKNOWN, List.of())})
                assertSame(base, EnchantPips.decorate(base, info, "UT"));
            assertNull(EnchantPips.decorate(null, EnchantInfo.ofSlotCount(2), "UT"));
        });
    }

    @Test public void aRetainedWideIconResolvesTierColorsOnEveryPaint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon base = new ImageIcon(new BufferedImage(70, 32, BufferedImage.TYPE_INT_ARGB));
            Icon decorated = EnchantPips.decorate(base, null, "ST");
            assertEquals(70, decorated.getIconWidth()); assertEquals(32, decorated.getIconHeight());
            try {
                for (Themes.Variant variant : Themes.Variant.values()) {
                    Themes.install(new Themes.Choice(variant, false));
                    BufferedImage image = icon(decorated); boolean inked = false;
                    for (int y = 0; y < 32; y++) for (int x = 0; x < 70; x++) {
                        int pixel = image.getRGB(x, y);
                        if (pixel != 0) assertTrue("Tier stays in the right-hand square", x >= 38 && y > 15);
                        inked |= pixel == Tokens.color(Tokens.Role.BAD).getRGB();
                    }
                    assertTrue(inked);
                }
            } finally { Themes.install(new Themes.Choice(Themes.Variant.DARK, false)); }
        });
    }

    private static BufferedImage corner(EnchantInfo info, String tier, int side) {
        BufferedImage image = new BufferedImage(side + 1, side + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { EnchantPips.paintCorner(g, info, tier, 0, 0, side); } finally { g.dispose(); }
        return image;
    }

    private static BufferedImage pips(EnchantInfo info, int side) {
        BufferedImage image = new BufferedImage(side + 1, side + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { EnchantPips.paint(g, info, 0, 0, side); } finally { g.dispose(); }
        return image;
    }

    private static BufferedImage icon(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { icon.paintIcon(new JLabel(), g, 0, 0); } finally { g.dispose(); }
        return image;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
