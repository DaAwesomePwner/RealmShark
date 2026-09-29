package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.accessibility.Accessible;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

/** ItemSlot.icon: the slot painted as an Icon for table and list renderers, drawn exactly as the ItemSlot component. */
public class ItemSlotIconTest {
    private static final int SIZE = 24, SIDE = SIZE + Sprites.WELL, MIDDLE = SIDE / 2;

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void theIconHasTheSlotComponentsFootprint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (ItemSlot.State state : ItemSlot.State.values()) {
                Icon icon = ItemSlot.icon(null, "", state, SIZE);
                assertEquals(SIDE, icon.getIconWidth());
                assertEquals(SIDE, icon.getIconHeight());
                assertEquals(new ItemSlot(SIZE).getPreferredSize(), new Dimension(icon.getIconWidth(), icon.getIconHeight()));
            }
            assertEquals(20 + Sprites.WELL, ItemSlot.icon(null, "", ItemSlot.State.ITEM, 20).getIconWidth());
        });
    }

    @Test public void anItemsBorderIsItsTierAndItsSpriteIsCenteredAndFitted() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                for (String tier : new String[]{"UT", "ST", "T12", ""}) {
                    Color edge = tier.isEmpty() ? Tokens.color(Tokens.Role.BORDER_SUBTLE) : Tokens.tier(tier);
                    BufferedImage image = paint(ItemSlot.icon(new Solid(10), tier, ItemSlot.State.ITEM, SIZE));
                    assertEquals(variant + " " + tier + ": the tier's border", edge.getRGB(), image.getRGB(0, MIDDLE));
                    assertEquals(edge.getRGB(), image.getRGB(SIDE - 1, MIDDLE));
                    assertEquals(Solid.INK.getRGB(), image.getRGB(MIDDLE, MIDDLE));
                }
                assertNotEquals("UT and ST differ", paint(ItemSlot.icon(new Solid(10), "UT", ItemSlot.State.ITEM, SIZE)).getRGB(0, MIDDLE),
                    paint(ItemSlot.icon(new Solid(10), "ST", ItemSlot.State.ITEM, SIZE)).getRGB(0, MIDDLE));
                // A 40 px sprite is scaled down to the well's room (SIZE px), centered: never over the border.
                BufferedImage large = paint(ItemSlot.icon(new Solid(40), "UT", ItemSlot.State.ITEM, SIZE));
                assertEquals(Solid.INK.getRGB(), large.getRGB(Sprites.WELL / 2, MIDDLE));
                assertEquals(Solid.INK.getRGB(), large.getRGB(SIDE - Sprites.WELL / 2 - 1, MIDDLE));
                assertNotEquals("The sprite stays inside the well", Solid.INK.getRGB(), large.getRGB(SIDE - Sprites.WELL / 2, MIDDLE));
                assertNotEquals(Solid.INK.getRGB(), large.getRGB(0, MIDDLE));
                // No sprite: the kit placeholder stands in, the well is still drawn.
                assertEquals(Tokens.tier("UT").getRGB(), paint(ItemSlot.icon(null, "UT", ItemSlot.State.ITEM, SIZE)).getRGB(0, MIDDLE));
            }
        });
    }

    @Test public void emptyAndNotCapturedLookDifferentAndIgnoreTheSprite() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                BufferedImage empty = paint(ItemSlot.icon(null, "", ItemSlot.State.EMPTY, SIZE));
                BufferedImage unknown = paint(ItemSlot.icon(null, "", ItemSlot.State.UNKNOWN, SIZE));
                assertFalse(variant + ": empty and not captured are told apart by their pixels", same(empty, unknown));
                int center = 0;
                for (int x = SIDE / 3; x < 2 * SIDE / 3; x++) for (int y = SIDE / 3; y < 2 * SIDE / 3; y++) if (empty.getRGB(x, y) != unknown.getRGB(x, y)) center++;
                assertTrue(variant + ": not captured marks its middle (the ? glyph)", center > 3);
                assertEquals(Tokens.color(Tokens.Role.SURFACE_ALT).getRGB(), empty.getRGB(2, MIDDLE));
                assertEquals(Tokens.color(Tokens.Role.RAISED).getRGB(), unknown.getRGB(2, MIDDLE));
                for (BufferedImage image : new BufferedImage[]{empty, unknown})
                    assertEquals("No tier edge without an item", Tokens.color(Tokens.Role.BORDER_SUBTLE).getRGB(), image.getRGB(0, MIDDLE));
                assertTrue("An empty slot paints no sprite and no tier", same(empty, paint(ItemSlot.icon(new Solid(10), "UT", ItemSlot.State.EMPTY, SIZE))));
                assertTrue(same(unknown, paint(ItemSlot.icon(new Solid(10), "UT", ItemSlot.State.UNKNOWN, SIZE))));
            }
        });
    }

    @Test public void theIconPaintsExactlyAsTheItemSlotComponent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                ItemSlot empty = new ItemSlot(SIZE); empty.setEmpty();
                ItemSlot unknown = new ItemSlot(SIZE);
                assertTrue(variant + ": empty", same(paint(empty), paint(ItemSlot.icon(null, "", ItemSlot.State.EMPTY, SIZE))));
                assertTrue(variant + ": not captured", same(paint(unknown), paint(ItemSlot.icon(null, "", ItemSlot.State.UNKNOWN, SIZE))));
                ItemSlot item = new ItemSlot(SIZE); item.setItem(987_654_321, "UT");
                assertTrue(variant + ": an item (placeholder sprite)", same(paint(item),
                    paint(ItemSlot.icon(Sprites.sprite(987_654_321, SIZE), "UT", ItemSlot.State.ITEM, SIZE))));
            }
        });
    }

    @Test public void theIconFiresNoAccessibilityEvents() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon icon = ItemSlot.icon(new Solid(10), "UT", ItemSlot.State.ITEM, SIZE);
            assertFalse("A painted stamp, not a component", icon instanceof Component || icon instanceof Accessible);
            JLabel host = new JLabel(icon);
            host.setSize(host.getPreferredSize());
            List<String> events = new ArrayList<>();
            host.getAccessibleContext().addPropertyChangeListener(event -> events.add(event.getPropertyName()));
            for (ItemSlot.State state : ItemSlot.State.values()) {
                Icon next = ItemSlot.icon(new Solid(10), "UT", state, SIZE);
                for (int i = 0; i < 3; i++) {
                    BufferedImage image = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = image.createGraphics();
                    try { next.paintIcon(host, g, 0, 0); host.paint(g); } finally { g.dispose(); }
                }
            }
            assertEquals("Painting announces nothing", List.of(), events);
        });
    }

    private static BufferedImage paint(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { icon.paintIcon(new JLabel(), g, 0, 0); } finally { g.dispose(); }
        return image;
    }

    private static BufferedImage paint(ItemSlot slot) {
        slot.setSize(slot.getPreferredSize());
        BufferedImage image = new BufferedImage(slot.getWidth(), slot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { slot.paint(g); } finally { g.dispose(); }
        return image;
    }

    private static boolean same(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int x = 0; x < a.getWidth(); x++) for (int y = 0; y < a.getHeight(); y++) if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
        return true;
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
