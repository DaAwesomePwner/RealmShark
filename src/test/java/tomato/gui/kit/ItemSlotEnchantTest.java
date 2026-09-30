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

/** ItemSlot with enchant data: rarity in its name, the shared enchant tooltip, the gem in the corner; slots without items drop it. */
public class ItemSlotEnchantTest {
    private static final EnchantInfo RARE = new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.RARE,
        List.of(new EnchantInfo.Slot(-1), new EnchantInfo.Slot(-1)));

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void anEnchantedItemNamesItsRarityAndShowsTheEnchantTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemSlot slot = new ItemSlot(24);
            slot.setItem(987_654_321, "UT", RARE);
            assertSame(RARE, slot.enchant());
            String name = slot.getAccessibleContext().getAccessibleName();
            assertTrue(name, name.endsWith(" · UT · Rare · 2 enchant slots"));
            String tip = slot.getToolTipText();
            assertTrue(tip, tip.startsWith("<html>") && tip.contains("Rare · 2 enchant slots") && tip.contains("(empty slot)"));
        });
    }

    @Test public void withoutEnchantDataTheSlotReadsAsBefore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemSlot slot = new ItemSlot(24);
            slot.setItem(987_654_321, "UT");
            assertNull(slot.enchant());
            assertFalse(slot.getToolTipText().startsWith("<html>"));
            assertTrue(slot.getToolTipText().endsWith(" · UT"));
        });
    }

    @Test public void emptyAndNotCapturedSlotsDropTheEnchant() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemSlot slot = new ItemSlot(24);
            slot.setItem(5, "T3", RARE); slot.setEmpty();
            assertNull(slot.enchant()); assertEquals("Empty slot", slot.getToolTipText());
            slot.setItem(5, "T3", RARE); slot.setUnknown();
            assertNull(slot.enchant()); assertEquals("Slot not captured", slot.getToolTipText());
            slot.setItem(0, "T3", RARE);
            assertEquals(ItemSlot.State.EMPTY, slot.state()); assertNull(slot.enchant());
        });
    }

    @Test public void theGemIsPaintedInTheComponentsTopRightCorner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                ItemSlot slot = new ItemSlot(24);
                slot.setItem(987_654_321, "UT", RARE);
                Dimension size = slot.getPreferredSize();
                slot.setSize(size);
                BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics(); slot.paint(g); g.dispose();
                int side = Math.min(size.width, size.height) - 1, gem = EnchantGem.size(side);
                assertEquals(variant + ": the Rare ink", Tokens.rarity(EnchantInfo.Rarity.RARE).getRGB(), image.getRGB(side - 2 - gem + gem / 2, 2 + gem / 2));
            }
        });
    }
}
