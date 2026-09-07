package assets;

import org.junit.Test;
import java.awt.Color;
import static org.junit.Assert.*;

public class ImageBufferTest {
    @Test public void emptyAndUnknownIdsRenderInNormalAndEnchantedEquipmentSlots() {
        for (int id : new int[]{0,-1,-2,65536}) {
            assertEquals(20,ImageBuffer.getOutlinedIcon(id,20).getIconWidth());
            assertEquals(26,ImageBuffer.getOutlinedIconWithGlow(id,20,Color.MAGENTA,3).getIconWidth());
        }
    }
}
