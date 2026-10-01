package tomato.gui.kit;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.JLabel;
import org.junit.Test;
import tomato.realmshark.enums.LootBags;
import static org.junit.Assert.*;

/** Bag sprites by saved bag name, the bag value order, and the tinted well when no sprite is available. */
public class BagSpritesTest {
    @Test public void everySavedBagNameMapsToItsObjectId() {
        for (Map.Entry<Integer, String> bag : LootBags.lootBagName.entrySet())
            assertEquals(bag.getValue(), (int) bag.getKey(), BagSprites.objectId(bag.getValue()));
        assertEquals(1292, BagSprites.objectId("white"));
        assertEquals(1296, BagSprites.objectId(" B.White "));
        assertEquals(0, BagSprites.objectId("Mystery"));
        assertEquals(0, BagSprites.objectId(null));
    }

    @Test public void bagsRankWhiteFirstWithBoostedJustAboveTheirBase() {
        List<String> order = List.of("White", "Red", "Orange", "Blue", "Teal", "Gold", "Egg Basket", "Purple", "Pink", "Soulbound", "Brown");
        for (int i = 1; i < order.size(); i++)
            assertTrue(order.get(i - 1) + " > " + order.get(i), BagSprites.rank(order.get(i - 1)) > BagSprites.rank(order.get(i)));
        assertTrue(BagSprites.rank("B.White") > BagSprites.rank("White"));
        assertTrue(BagSprites.rank("B.Red") < BagSprites.rank("White"));
        assertTrue(BagSprites.rank("B.Egg") > BagSprites.rank("Egg Basket"));
        assertTrue(BagSprites.rank("B.Egg") < BagSprites.rank("Gold"));
        assertTrue("Every known bag outranks an unknown one", BagSprites.rank("Brown") > 0);
        assertEquals(0, BagSprites.rank("Mystery"));
        assertEquals(0, BagSprites.rank(null));
    }

    @Test public void withoutASpriteABagPaintsATintedWellNeverABlank() {
        Sprites.clear();
        try {
            Icon unknown = BagSprites.sprite("Mystery", 24);
            assertEquals(24, unknown.getIconWidth());
            assertEquals(24, unknown.getIconHeight());
            assertTrue(BagSprites.isFallback(unknown));
            BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            unknown.paintIcon(new JLabel(), g, 0, 0);
            g.dispose();
            boolean painted = false;
            for (int y = 0; y < 24 && !painted; y++) for (int x = 0; x < 24; x++) if ((image.getRGB(x, y) >>> 24) != 0) { painted = true; break; }
            assertTrue("The well is painted", painted);
            assertEquals(24, BagSprites.sprite("White", 24).getIconWidth());
        } finally { Sprites.clear(); }
    }
}
