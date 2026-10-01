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

/** ItemSlot with enchant data: rarity in its name, the shared enchant tooltip and bottom-right pips; non-item slots drop them. */
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

    @Test public void thePipsArePaintedInTheComponentsBottomRightCorner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                ItemSlot slot = new ItemSlot(24);
                slot.setItem(987_654_321, "UT", RARE);
                Dimension size = slot.getPreferredSize();
                slot.setSize(size);
                BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics(); slot.paint(g); g.dispose();
                int side = Math.min(size.width, size.height) - 1, pip = EnchantPips.size(side, 2), center = side - 1 - pip + pip / 2;
                for (int i = 0; i < 2; i++) assertEquals(variant + ": the Rare ink", Tokens.rarity(EnchantInfo.Rarity.RARE).getRGB(),
                    image.getRGB(center - i * (pip + EnchantPips.gap(side, 2)), center));
            }
        });
    }

    @Test public void iconStampsMatchComponentsAndNonItemStatesIgnoreEnchants() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int size : new int[] {20, 24, 32, 48}) for (ItemSlot.State state : ItemSlot.State.values())
                for (EnchantInfo info : new EnchantInfo[] {null, RARE, EnchantInfo.unreadable()}) {
                    ItemSlot slot = new ItemSlot(size);
                    if (state == ItemSlot.State.ITEM) slot.setItem(987_654_321, "UT", info);
                    else if (state == ItemSlot.State.EMPTY) slot.setEmpty();
                    Icon stamp = ItemSlot.icon(Sprites.sprite(987_654_321, size), "UT", state, size, info);
                    Dimension bounds = slot.getPreferredSize(); slot.setSize(bounds);
                    BufferedImage component = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = component.createGraphics(); slot.paint(g); g.dispose();
                    BufferedImage icon = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
                    g = icon.createGraphics(); stamp.paintIcon(slot, g, 0, 0); g.dispose();
                    assertArrayEquals(state + " size=" + size, component.getRGB(0, 0, bounds.width, bounds.height, null, 0, bounds.width),
                        icon.getRGB(0, 0, bounds.width, bounds.height, null, 0, bounds.width));
                }
        });
    }

    @Test public void unenchantedTierTextAppearsInsideLargeComponentsAndStampsOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int size : new int[] {20, 32}) for (boolean stamp : new boolean[] {false, true}) {
                BufferedImage plain = tierSlot(size, "", stamp), tiered = tierSlot(size, "UT", stamp);
                int warn = Tokens.color(Tokens.Role.WARN).getRGB(), textPixels = 0;
                // Exclude the rounded tier border: its WARN pixels are present even below the text-size threshold.
                for (int y = 4; y < tiered.getHeight() - 4; y++) for (int x = 4; x < tiered.getWidth() - 4; x++) {
                    if (tiered.getRGB(x, y) != warn || plain.getRGB(x, y) == warn) continue;
                    textPixels++;
                    assertTrue("UT text stays in the bottom-right quarter inside the edge",
                        x >= tiered.getWidth() / 2 && y >= tiered.getHeight() / 2);
                }
                assertEquals((stamp ? "Stamp" : "Component") + " size=" + size + ": UT text uses WARN only in large wells", size == 32, textPixels > 0);
            }
        });
    }

    private static BufferedImage tierSlot(int size, String tier, boolean stamp) {
        ItemSlot slot = new ItemSlot(size);
        slot.setItem(987_654_321, tier, null);
        Dimension bounds = slot.getPreferredSize(); slot.setSize(bounds);
        BufferedImage image = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            if (stamp) ItemSlot.icon(Sprites.sprite(987_654_321, size), tier, ItemSlot.State.ITEM, size, null).paintIcon(slot, g, 0, 0);
            else slot.paint(g);
        } finally { g.dispose(); }
        return image;
    }
}
