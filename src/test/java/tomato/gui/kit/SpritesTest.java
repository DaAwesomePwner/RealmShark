package tomato.gui.kit;

import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.modern.LineIcon;
import static org.junit.Assert.*;

public class SpritesTest {
    @Test public void retainedSpriteRefreshesAfterAssetsArePublishedAndReplaced() throws Exception {
        try (assets.AssetGenerationFixture fixture = new assets.AssetGenerationFixture()) {
            Sprites.clear();
            Icon retained = Sprites.sprite(assets.AssetGenerationFixture.ITEM, 24);
            assertTrue(Sprites.isPlaceholder(retained));
            fixture.install(false);
            assertFalse(Sprites.isPlaceholder(retained));
            assertEquals(java.awt.Color.RED.getRGB(), pixel(retained));
            fixture.install(true);
            assertSame(retained, Sprites.sprite(assets.AssetGenerationFixture.ITEM, 24));
            assertEquals(java.awt.Color.BLUE.getRGB(), pixel(retained));
        } finally { Sprites.clear(); }
    }

    private int pixel(Icon icon) {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        icon.paintIcon(new JLabel(), graphics, 0, 0); graphics.dispose();
        return image.getRGB(12, 12);
    }

    @Test public void emptyAndUnknownObjectsGetAPlaceholderNotABlank() {
        Icon empty = Sprites.sprite(0, 24), missing = Sprites.sprite(987_654_321, 24);
        assertTrue(Sprites.isPlaceholder(empty));
        assertTrue(Sprites.isPlaceholder(missing));
        assertEquals(24, missing.getIconWidth());
        assertSame("Cached per id and size", missing, Sprites.sprite(987_654_321, 24));
        Sprites.clear();
        assertNotSame(missing, Sprites.sprite(987_654_321, 24));
    }

    @Test public void namesFallBackToTheId() {
        assertEquals("Empty", Sprites.name(-1));
        assertEquals("Unknown item #987654321", Sprites.name(987_654_321));
    }

    @Test public void newGlyphsPaintWithoutErrors() {
        BufferedImage image = new BufferedImage(22, 22, BufferedImage.TYPE_INT_ARGB);
        JLabel host = new JLabel();
        for (int type : new int[]{LineIcon.HOME, LineIcon.SWORDS, LineIcon.DIAMOND, LineIcon.CHECKLIST, LineIcon.GEAR,
                LineIcon.FILTER, LineIcon.DOTS, LineIcon.CHEVRON_DOWN, LineIcon.CHEVRON_RIGHT, LineIcon.PIN, LineIcon.STAR,
                LineIcon.HOURGLASS, LineIcon.REFRESH, LineIcon.PENCIL, LineIcon.CLOCK, LineIcon.CLOSE, LineIcon.SEARCH, LineIcon.GRIP}) {
            java.awt.Graphics2D g = image.createGraphics();
            new LineIcon(type, 22).paintIcon(host, g, 0, 0);
            g.dispose();
        }
    }
}
