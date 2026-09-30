package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.EnchantInfo.Rarity;
import static org.junit.Assert.*;

/** The rarity gem: one color per rarity, in the well's top-right corner inside its edge, resolved from the theme at paint time. */
public class EnchantGemTest {
    private static final Rarity[] GEMS = {Rarity.UNCOMMON, Rarity.RARE, Rarity.LEGENDARY, Rarity.DIVINE};

    static EnchantInfo recorded(Rarity rarity) {
        return new EnchantInfo(EnchantInfo.State.RECORDED, rarity, java.util.Collections.nCopies(rarity.ordinal(), new EnchantInfo.Slot(-1)));
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void eachRarityHasItsOwnColorInEveryVariant() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                Set<Integer> inks = new HashSet<>();
                for (Rarity rarity : GEMS) inks.add(Tokens.rarity(rarity).getRGB());
                assertEquals(variant + ": four distinct gem colors", 4, inks.size());
                assertNull(Tokens.rarity(Rarity.UNENCHANTED));
                assertNull(Tokens.rarity(Rarity.UNKNOWN));
            }
        });
    }

    @Test public void noGemForUnenchantedNotRecordedOrNoData() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon base = ItemSlot.icon(null, "UT", ItemSlot.State.ITEM, 20);
            assertSame(base, EnchantGem.decorate(base, recorded(Rarity.UNENCHANTED)));
            assertSame(base, EnchantGem.decorate(base, EnchantInfo.notRecorded()));
            assertSame(base, EnchantGem.decorate(base, null));
            assertNull(EnchantGem.decorate(null, recorded(Rarity.RARE)));
        });
    }

    @Test public void theGemPaintsItsInkInTheTopRightCornerInsideTheEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                Icon plain = ItemSlot.icon(null, "UT", ItemSlot.State.ITEM, 20);
                int w = plain.getIconWidth(), side = w - 1, gem = EnchantGem.size(side);
                int cx = side - 2 - gem + gem / 2, cy = 2 + gem / 2;
                for (Rarity rarity : GEMS) {
                    int[] with = paint(EnchantGem.decorate(plain, recorded(rarity))), without = paint(plain);
                    assertEquals(variant + " " + rarity + ": the gem's ink", Tokens.rarity(rarity).getRGB(), with[cy * w + cx]);
                    for (int i = 0; i < with.length; i++) {
                        if (with[i] == without[i]) continue;
                        int x = i % w, y = i / w;
                        assertTrue("Inside the edge at " + x + "," + y, x > 0 && y > 0 && x < w - 1 && y < w - 1);
                        assertTrue("In the top-right quarter at " + x + "," + y, x >= w / 2 && y < w / 2);
                    }
                }
                int[] unreadable = paint(EnchantGem.decorate(plain, EnchantInfo.unreadable()));
                assertEquals(variant + ": unreadable data is a muted gem", Tokens.color(Tokens.Role.TEXT_MUTED).getRGB(), unreadable[cy * w + cx]);
            }
        });
    }

    @Test public void theGemStaysLegibleFromTwentyToFortyEightPixels() {
        assertTrue(EnchantGem.size(20 + Sprites.WELL - 1) >= 6);
        assertTrue(EnchantGem.size(48 + Sprites.WELL - 1) <= 16);
    }

    private static int[] paint(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        icon.paintIcon(new JLabel(), g, 0, 0);
        g.dispose();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
