package realmshark.branding;

import realmshark.version.Version;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import java.awt.AWTEvent;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Shared public identity for entrypoints, Swing windows, and desktop integration. */
public final class AppIdentity {
    public static final String NAME = "RealmShark";
    public static final String APP_USER_MODEL_ID = "RealmShark.Desktop";
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private static final AtomicBoolean NATIVE_FAILURE_REPORTED = new AtomicBoolean();
    private static boolean initialized;

    private AppIdentity() { }

    public static String version() { return Version.VERSION; }
    public static String title() { return NAME + " " + version(); }

    /** Read-only size-ordered list; callers should treat the shared image pixels as immutable. */
    public static List<Image> icons() { return Images.ALL; }

    public static ImageIcon icon(int size) {
        for (Image image : icons()) {
            if (image.getWidth(null) == size) return new ImageIcon(image, NAME);
        }
        return new ImageIcon(FinIcon.image(size), NAME);
    }

    /** Call before creating windows. Safe to call from both the adapter and legacy entrypoint. */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        if (GraphicsEnvironment.isHeadless()) return;
        if (WINDOWS) {
            try { WindowsAppIdentity.initializeProcess(); }
            catch (RuntimeException | LinkageError ex) { nativeFailure(ex); }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event.getID() == WindowEvent.WINDOW_OPENED) apply(((WindowEvent) event).getWindow());
            else if (event.getID() == WindowEvent.WINDOW_CLOSING && event.getSource() instanceof JFrame
                    && ((JFrame) event.getSource()).getDefaultCloseOperation() == JFrame.EXIT_ON_CLOSE) {
                // Toolkit listeners run before JFrame's EXIT_ON_CLOSE calls System.exit.
                prepareExit();
            }
        }, AWTEvent.WINDOW_EVENT_MASK);
    }

    /** Clear live Shell properties before the JVM's concurrent AWT shutdown can destroy HWNDs. */
    public static void exit(int status) {
        try { prepareExit(); }
        finally { System.exit(status); }
    }

    private static void prepareExit() {
        if (WINDOWS) {
            try { WindowsAppIdentity.shutdown(); }
            catch (RuntimeException | LinkageError ex) { nativeFailure(ex); }
        }
    }

    /** Apply on the window's UI thread, preferably before pack/show; does not create a native peer. */
    public static void apply(Window window) {
        if (window == null) return;
        initialize();
        window.setIconImages(icons());
        if (WINDOWS) {
            try { WindowsAppIdentity.attach(window); }
            catch (RuntimeException | LinkageError ex) { nativeFailure(ex); }
        }
    }

    static void nativeFailure(Throwable ex) {
        if (NATIVE_FAILURE_REPORTED.compareAndSet(false, true)) {
            Logger.getLogger(AppIdentity.class.getName()).warning("Windows taskbar identity unavailable: " + ex);
        }
    }

    private static final class Images {
        private static final List<Image> ALL = load();

        private static List<Image> load() {
            List<Image> images = new ArrayList<>();
            for (int size : FinIcon.sizes()) {
                BufferedImage image = null;
                try (InputStream stream = AppIdentity.class.getResourceAsStream("/icon/realmshark-" + size + ".png")) {
                    if (stream != null) image = ImageIO.read(stream);
                } catch (IOException ignored) { /* Source-only IDE launches can render the same artwork. */ }
                if (image == null || image.getWidth() != size || image.getHeight() != size) image = FinIcon.image(size);
                images.add(image);
            }
            return Collections.unmodifiableList(images);
        }
    }
}
