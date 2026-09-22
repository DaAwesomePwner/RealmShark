package tomato.gui.stats;

import assets.AssetGenerationFixture;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LootIconGenerationTest {
    @Test public void retainedDashboardIconResolvesTheNewGenerationWithoutNewLootRows() throws Exception {
        try (AssetGenerationFixture fixture = new AssetGenerationFixture()) {
            fixture.install(false);
            LootDashboard[] view = new LootDashboard[1]; Icon[] retained = new Icon[1];
            SwingUtilities.invokeAndWait(() -> { view[0] = new LootDashboard(); retained[0] = view[0].iconForItem(AssetGenerationFixture.ITEM); });
            assertEquals(Color.RED.getRGB(), center(retained[0]));
            fixture.install(true);
            SwingUtilities.invokeAndWait(() -> assertSame(retained[0], view[0].iconForItem(AssetGenerationFixture.ITEM)));
            assertEquals(Color.BLUE.getRGB(), center(retained[0]));
        }
    }
    private static int center(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { icon.paintIcon(null, graphics, 0, 0); } finally { graphics.dispose(); }
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
    }
}
