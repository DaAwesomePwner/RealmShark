package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;

/** Loot pips preserve slot counts at small sizes; halos preserve the sprite and follow the theme without tinting placeholders. */
public class EnchantPipsTest {
    @Test public void pipsCountSlotsAndStayInsideTheBottomEdgeInEveryTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (Themes.Variant variant : Themes.Variant.values()) {
                    Themes.install(new Themes.Choice(variant, false));
                    for (int side : new int[] {19, 23, 25, 39, 47, 53}) for (int count = 1; count <= 4; count++) {
                        EnchantInfo info = EnchantInfo.ofSlotCount(count);
                        BufferedImage image = pips(info, side);
                        int runs = 0, rightmost = -1; boolean previous = false;
                        for (int x = 0; x <= side; x++) {
                            boolean colored = false;
                            for (int y = 0; y <= side; y++) {
                                int pixel = image.getRGB(x, y);
                                if ((pixel >>> 24) != 0) {
                                    assertTrue("Inside the edge and in the bottom half", x > 0 && x < side && y > side / 2 && y < side);
                                    rightmost = Math.max(rightmost, x);
                                }
                                colored |= pixel == Tokens.rarity(info.rarity()).getRGB();
                            }
                            if (colored && !previous) runs++;
                            previous = colored;
                        }
                        assertEquals(variant + " side=" + side, count, runs);
                        assertEquals("Rightmost pip touches the two-pixel right inset", side - 2, rightmost);
                        int d = EnchantPips.size(side, count), gap = EnchantPips.gap(d);
                        if (side == 23 && count == 4) assertTrue("Table pips stay legible", d >= 4);
                        if (side == 39 && count == 4) assertEquals("Card pips stay six pixels", 6, d);
                        int center = side - 1 - d + d / 2;
                        for (int i = 0; i < count; i++) assertEquals("Every pip shares one horizontal row",
                            Tokens.rarity(info.rarity()).getRGB(), image.getRGB(center - i * (d + gap), center));
                        for (int i = 1; i < count; i++) {
                            int blank = side - 1 - d - (i - 1) * (d + gap) - 1;
                            for (int y = 0; y <= side; y++) assertEquals("A clear column separates pips", 0, image.getRGB(blank, y));
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
            assertEquals(0, unreadable.getRGB(center - d - EnchantPips.gap(d), center));
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
            BufferedImage image = icon(new ItemIcon(base, "Bow", EnchantInfo.ofSlotCount(2)));
            int changed = 0;
            for (int y = 0; y < 21; y++) for (int x = 0; x < 60; x++) if (image.getRGB(x, y) != 0) {
                changed++;
                assertTrue(x >= 39 && x < 59 && y > 10 && y < 20);
            }
            assertTrue(changed > 0);
        });
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
