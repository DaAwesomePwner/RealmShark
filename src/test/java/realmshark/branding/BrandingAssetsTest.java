package realmshark.branding;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.*;

public class BrandingAssetsTest {
    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128, 256};

    @Test public void pngResourcesMatchCanonicalArtworkAtEveryDesktopScale() throws Exception {
        assertArrayEquals(SIZES, FinIcon.sizes());
        for (int size : SIZES) {
            BufferedImage png = ImageIO.read(new ByteArrayInputStream(resource("realmshark-" + size + ".png")));
            assertNotNull(png);
            assertEquals(size, png.getWidth());
            assertEquals(size, png.getHeight());
            assertTrue(png.getColorModel().hasAlpha());
            assertPixelsEqual(FinIcon.image(size), png);
            assertTrue("Rounded corner stays transparent (with edge antialiasing)", (png.getRGB(0, 0) >>> 24) < 64);
            assertEquals("Badge interior stays opaque", 255, png.getRGB(size / 2, size / 2) >>> 24);
            int violetPixels = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = png.getRGB(x, y);
                    int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
                    if ((pixel >>> 24) > 240 && r > 140 && b > 200 && b > g + 30) violetPixels++;
                }
            }
            assertTrue("Fin remains legible at " + size + "px", violetPixels > size * size / 10);
        }
    }

    @Test public void icoDirectoryAndEmbeddedImagesMatchPngResourcesExactly() throws Exception {
        byte[] bytes = resource("realmshark.ico");
        ByteBuffer ico = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, ico.getShort());
        assertEquals(1, ico.getShort());
        assertEquals(SIZES.length, ico.getShort());
        int nextOffset = 6 + 16 * SIZES.length;
        for (int size : SIZES) {
            int width = Byte.toUnsignedInt(ico.get()), height = Byte.toUnsignedInt(ico.get());
            assertEquals(size, width == 0 ? 256 : width);
            assertEquals(size, height == 0 ? 256 : height);
            assertEquals(0, ico.get()); // True color, no palette.
            assertEquals(0, ico.get()); // Reserved.
            assertEquals(1, ico.getShort());
            assertEquals(32, ico.getShort());
            int length = ico.getInt(), offset = ico.getInt();
            assertEquals(nextOffset, offset);
            assertTrue(length > 8 && offset + length <= bytes.length);
            byte[] png = resource("realmshark-" + size + ".png");
            assertArrayEquals(png, Arrays.copyOfRange(bytes, offset, offset + length));
            assertEquals(size, ImageIO.read(new ByteArrayInputStream(png)).getWidth());
            nextOffset += length;
        }
        assertEquals("No unindexed icon payload", bytes.length, nextOffset);
    }

    @Test public void oldPublicIconIsNotPackaged() {
        assertNull(AppIdentity.class.getResource("/icon/tomatoIcon.png"));
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream stream = AppIdentity.class.getResourceAsStream("/icon/" + name)) {
            assertNotNull("Generated branding resource: " + name, stream);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    static void assertPixelsEqual(BufferedImage expected, BufferedImage actual) {
        int width = expected.getWidth(), height = expected.getHeight();
        assertArrayEquals(expected.getRGB(0, 0, width, height, null, 0, width),
                actual.getRGB(0, 0, width, height, null, 0, width));
    }
}
