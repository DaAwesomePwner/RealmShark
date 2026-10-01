package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;

/** A table-cell item icon: the base sprite, bottom-right rarity pips, and the shared tooltip built when asked for. */
public class ItemIconTest {
    private static final EnchantInfo RARE = new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.RARE,
        List.of(new EnchantInfo.Slot(-1), new EnchantInfo.Slot(-1)));

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void paintsTheBaseThenThePipsAndKeepsTheBasesSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon base = new Solid(24);
            ItemIcon icon = new ItemIcon(base, "Doom Bow", RARE);
            assertSame(base, icon.base());
            assertEquals(24, icon.getIconWidth()); assertEquals(24, icon.getIconHeight());
            BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics(); icon.paintIcon(new JLabel(), g, 0, 0); g.dispose();
            int side = 23, pip = EnchantPips.size(side, 2), center = side - 1 - pip + pip / 2;
            for (int i = 0; i < 2; i++) assertEquals(Tokens.rarity(EnchantInfo.Rarity.RARE).getRGB(),
                image.getRGB(center - i * (pip + EnchantPips.gap(pip)), center));
            assertEquals("The base shows elsewhere", Solid.INK.getRGB(), image.getRGB(4, 20));
        });
    }

    @Test public void theTooltipIsBuiltWhenAskedAndFollowsTheTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemIcon icon = new ItemIcon(new Solid(24), "Doom Bow", RARE);
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                String hex = String.format("#%06x", Tokens.rarity(EnchantInfo.Rarity.RARE).getRGB() & 0xFFFFFF);
                assertTrue(variant.toString(), icon.tooltip().startsWith("<html><b>Doom Bow</b>") && icon.tooltip().contains(hex));
            }
            assertNull("No enchant data, no tooltip", new ItemIcon(new Solid(24), "Potion", EnchantInfo.notRecorded()).tooltip());
        });
    }

    static final class Solid implements Icon {
        static final Color INK = new Color(200, 40, 90);
        private final int size;
        Solid(int size) { this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { g.setColor(INK); g.fillRect(x, y, size, size); }
    }
}
