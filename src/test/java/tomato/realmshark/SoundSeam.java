package tomato.realmshark;

import java.util.concurrent.atomic.AtomicInteger;

/** Test-only access to Sound's package-local submission seam; no audio device is ever opened. */
public final class SoundSeam {
    private SoundSeam() {}
    /** Counts every submission (event playback or Test) instead of reaching the audio worker. */
    public static void count(AtomicInteger counter) { Sound.playbackOverride = (sound, preview) -> counter.incrementAndGet(); }
    public static void clear() { Sound.playbackOverride = null; }
}
