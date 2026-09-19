package realmshark.branding;

import org.junit.Assume;
import org.junit.Test;
import realmshark.version.Version;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.Assert.*;

public class AppIdentityTest {
    @Test public void identityUsesTheGeneratedRealmSharkVersion() {
        assertEquals("RealmShark", AppIdentity.NAME);
        assertEquals(Version.VERSION, AppIdentity.version());
        assertEquals("RealmShark " + Version.VERSION, AppIdentity.title());
        assertEquals("RealmShark.Desktop", AppIdentity.APP_USER_MODEL_ID);
    }

    @Test public void iconApiSuppliesEverySizeAndRendersIntermediateSizes() {
        List<Image> images = AppIdentity.icons();
        int[] sizes = {16, 20, 24, 32, 48, 64, 128, 256};
        assertEquals(sizes.length, images.size());
        for (int i = 0; i < sizes.length; i++) {
            ImageIcon icon = AppIdentity.icon(sizes[i]);
            assertSame(images.get(i), icon.getImage());
            assertEquals(sizes[i], icon.getIconWidth());
            assertEquals(sizes[i], icon.getIconHeight());
            assertEquals("RealmShark", icon.getDescription());
        }
        ImageIcon custom = AppIdentity.icon(37);
        assertEquals(37, custom.getIconWidth());
        BrandingAssetsTest.assertPixelsEqual(FinIcon.image(37), (BufferedImage) custom.getImage());
        try { images.clear(); fail("Shared icon list must be read-only"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void invalidIconSizesFailBeforeAllocatingAnImage() {
        for (int size : new int[]{Integer.MIN_VALUE, -1, 0, 1025, Integer.MAX_VALUE}) {
            try { AppIdentity.icon(size); fail("Invalid size accepted: " + size); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void initializeIsIdempotentAndApplyDoesNotCreateAPeer() throws Exception {
        AppIdentity.initialize();
        AppIdentity.apply(null);
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        int listeners = Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.WINDOW_EVENT_MASK).length;
        AppIdentity.initialize();
        assertEquals(listeners, Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.WINDOW_EVENT_MASK).length);
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Specific tool title");
            try {
                AppIdentity.apply(frame);
                int bindings = frame.getHierarchyListeners().length;
                AppIdentity.apply(frame);
                assertEquals(bindings, frame.getHierarchyListeners().length);
                assertFalse(frame.isDisplayable());
                assertEquals("Specific tool title", frame.getTitle());
                assertEquals(AppIdentity.icons(), frame.getIconImages());
            } finally { frame.dispose(); }
        });
    }

    @Test public void openedWindowFallbackBrandsIndependentFrames() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        AppIdentity.initialize();
        JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame[0] = new JFrame();
                frame[0].setSize(80, 80);
                frame[0].setLocation(-10000, -10000);
                frame[0].setVisible(true);
            });
            SwingUtilities.invokeAndWait(() -> assertEquals(AppIdentity.icons(), frame[0].getIconImages()));
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); });
        }
    }
}
