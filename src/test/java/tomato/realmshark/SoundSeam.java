package tomato.realmshark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only access to Sound's package-local seams; no audio device is ever opened. */
public final class SoundSeam {
    private SoundSeam() {}
    /** Counts every submission (event playback or Test) instead of reaching the audio worker. */
    public static void count(AtomicInteger counter) { Sound.playbackOverride = (sound, preview) -> counter.incrementAndGet(); }
    /** Real submission path (worker, mute/volume checks, decision completion) with a fake device that starts. */
    public static void device(String name, AtomicInteger plays) {
        Sound.playbackOverride = null;
        Sound.deviceOverride = (sound, started, failed) -> { plays.incrementAndGet(); started.accept(name); };
    }
    /** Real submission path with a fake device that reports an unavailable output. */
    public static void unavailable(String reason) {
        Sound.playbackOverride = null;
        Sound.deviceOverride = (sound, started, failed) -> failed.accept(reason);
    }
    public static void clear() { Sound.playbackOverride = null; Sound.deviceOverride = null; }
    /** Waits (off the EDT) for the asynchronous playback result of a decision. */
    public static AlertDecisions.Decision settled(long id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AlertDecisions.Decision decision = AlertDecisions.INSTANCE.find(id);
        while (decision != null && decision.result == AlertDecisions.Result.SUBMITTED && System.nanoTime() < deadline) {
            Thread.sleep(10); decision = AlertDecisions.INSTANCE.find(id);
        }
        return decision;
    }
}
