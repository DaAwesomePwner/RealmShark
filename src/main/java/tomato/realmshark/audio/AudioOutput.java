package tomato.realmshark.audio;

import javax.sound.sampled.*;
import java.util.Locale;
import java.util.function.Consumer;

/** Audio devices are opened off the UI/capture thread. */
public final class AudioOutput {
    private AudioOutput() { }
    public interface Playback { void stop(); }

    public static Playback play(AudioFormat format, byte[] pcm, Consumer<String> route, Consumer<String> failure) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            return WindowsHolder.ENGINE.play(format, pcm, route, failure);
        Clip clip = AudioSystem.getClip();
        try {
            clip.open(format, pcm, 0, pcm.length);
            clip.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) clip.close(); });
            clip.start();
            route.accept("the system default output");
            return () -> { clip.stop(); clip.close(); };
        } catch (Exception e) { clip.close(); throw e; }
    }

    // Lazy: non-Windows machines and decoding/mute tests never load native Windows libraries.
    private static final class WindowsHolder {
        static final DefaultAudioEngine ENGINE = new DefaultAudioEngine(new WasapiBackend());
        static {
            Thread worker = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    ENGINE.tick(System.nanoTime());
                    try { Thread.sleep(10); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                ENGINE.close();
            }, "notification-wasapi");
            worker.setDaemon(true);
            worker.start();
        }
    }
}
