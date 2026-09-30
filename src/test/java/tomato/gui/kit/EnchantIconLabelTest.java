package tomato.gui.kit;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.util.List;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;

public class EnchantIconLabelTest {
    private static EnchantInfo rare() {
        return new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.RARE,
            List.of(new EnchantInfo.Slot(-1), new EnchantInfo.Slot(-1)));
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void tooltipColorsFollowEveryThemeWithoutSettingTheItemAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EnchantIconLabel label = new EnchantIconLabel();
            label.setItem(new Solid(), "Doom Bow", rare());
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                String hex = String.format("#%06x", Tokens.rarity(EnchantInfo.Rarity.RARE).getRGB() & 0xFFFFFF);
                assertTrue(variant.toString(), label.getToolTipText().contains(hex));
            }
        });
    }

    @Test public void accessibleNameEndsWithTheEnchantSummary() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EnchantIconLabel label = new EnchantIconLabel();
            EnchantInfo enchant = rare();
            label.setItem(new Solid(), "Doom Bow", enchant);
            assertEquals("Doom Bow · Rare · 2 enchant slots", label.getAccessibleContext().getAccessibleName());
            label.setItem(new Solid(), null, enchant);
            assertEquals(enchant.summary(), label.getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void nullEnchantPreservesTheIconAndPlainHeading() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EnchantIconLabel label = new EnchantIconLabel();
            Icon icon = new Solid();
            label.setItem(icon, "Doom Bow", null);
            assertSame(icon, label.getIcon());
            assertEquals("Doom Bow", label.getToolTipText());
            assertEquals("Doom Bow", label.getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void clearRemovesTheItemAndTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EnchantIconLabel label = new EnchantIconLabel();
            label.setItem(new Solid(), "Doom Bow", rare());
            label.clear();
            assertNull(label.getIcon());
            assertNull(label.enchant());
            assertNull(label.getToolTipText());
            assertEquals("", label.getAccessibleContext().getAccessibleName());
        });
    }

    private static final class Solid implements Icon {
        @Override public int getIconWidth() { return 12; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(new Color(200, 40, 90));
            g.fillRect(x, y, 12, 12);
        }
    }
}
