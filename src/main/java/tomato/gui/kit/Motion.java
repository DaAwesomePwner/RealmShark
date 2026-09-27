package tomato.gui.kit;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.function.DoubleConsumer;
import javax.swing.Timer;
import util.PropertiesManager;

/** Short orientation motion only. Off with Settings › Reduce motion or Windows "Animate controls" off. */
public final class Motion {
    public static final int MAX_MILLIS = 100;
    public static final String REDUCE_KEY = "ui.reduceMotion";
    private static final int SPI_GETCLIENTAREAANIMATION = 0x1042;
    /** Replaced by tests only; null reads the operating system. */
    static volatile Boolean systemOverride;

    private Motion() {}

    public static boolean enabled() {
        return !"true".equals(PropertiesManager.getProperty(REDUCE_KEY)) && systemAnimations();
    }

    /**
     * Calls frame with eased progress in (0, 1], then done, on the EDT. Durations are capped at
     * MAX_MILLIS. When motion is off, frame(1) and done run immediately and null is returned.
     */
    public static Timer run(int millis, DoubleConsumer frame, Runnable done) {
        int duration = Math.min(MAX_MILLIS, Math.max(0, millis));
        if (duration == 0 || !enabled()) {
            frame.accept(1);
            if (done != null) done.run();
            return null;
        }
        long start = System.nanoTime();
        Timer timer = new Timer(15, null);
        timer.addActionListener(event -> {
            double t = Math.min(1, (System.nanoTime() - start) / 1_000_000.0 / duration);
            frame.accept(1 - Math.pow(1 - t, 3));
            if (t >= 1) {
                timer.stop();
                if (done != null) done.run();
            }
        });
        timer.start();
        return timer;
    }

    private static volatile long systemCheckedAt;
    private static volatile boolean systemAllows = true;

    /** Reads the Windows setting at most every five seconds; animations are rare, but this avoids a JNA call per frame burst. */
    private static boolean systemAnimations() {
        Boolean override = systemOverride;
        if (override != null) return override;
        if (!Platform.isWindows()) return true;
        long now = System.nanoTime();
        if (systemCheckedAt != 0 && now - systemCheckedAt < 5_000_000_000L) return systemAllows;
        boolean allows;
        try {
            IntByReference value = new IntByReference(1);
            allows = !User32.INSTANCE.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, value, 0) || value.getValue() != 0;
        } catch (Throwable unavailable) {
            allows = true;
        }
        systemAllows = allows;
        systemCheckedAt = now;
        return allows;
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);
        boolean SystemParametersInfoW(int action, int param, IntByReference value, int winIni);
    }
}
