package tomato.gui.modern;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.swing.JLabel;
import org.junit.Test;
import static org.junit.Assert.*;

public class LineIconTest {
    @Test public void typeEightStaysTheFinBrandMark() {
        int[] fin = pixels(new LineIcon(999, 22));
        assertArrayEquals("Type 8 is the RealmShark fin shown beside the product name", fin, pixels(new LineIcon(8, 22)));
        assertFalse("Kit glyphs must not replace the fin", Arrays.equals(fin, pixels(new LineIcon(LineIcon.HOME, 22))));
    }

    private static int[] pixels(LineIcon icon) {
        BufferedImage image = new BufferedImage(22, 22, BufferedImage.TYPE_INT_ARGB);
        JLabel host = new JLabel();
        host.setForeground(Color.BLACK);
        Graphics2D g = image.createGraphics();
        icon.paintIcon(host, g, 0, 0);
        g.dispose();
        return image.getRGB(0, 0, 22, 22, null, 0, 22);
    }
}
