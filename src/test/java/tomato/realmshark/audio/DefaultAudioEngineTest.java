package tomato.realmshark.audio;

import org.junit.Test;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.Assert.*;

public class DefaultAudioEngineTest {
    private static final AudioFormat FORMAT = new AudioFormat(44100, 16, 2, true, false);
    private static final byte[] PCM = new byte[400]; // 100 stereo frames
    private static final DefaultAudioEngine.Device AMR = new DefaultAudioEngine.Device("amr-id", "AMR headset");
    private static final DefaultAudioEngine.Device G560 = new DefaultAudioEngine.Device("g560-id", "G560 speakers");
    private final FakeBackend backend = new FakeBackend();
    private final DefaultAudioEngine engine = new DefaultAudioEngine(backend);
    private final List<String> routes = new ArrayList<>(), failures = new ArrayList<>();
    private AudioOutput.Playback play() { return engine.play(FORMAT, PCM, routes::add, failures::add); }

    @Test public void resolvesDefaultAgainForEveryAlertAfterSwitchingBothWays() {
        play(); engine.tick(0);
        backend.device = G560; play(); engine.tick(1);
        backend.device = AMR; play(); engine.tick(2);
        assertEquals("amr-id", backend.opened.get(0).device.id);
        assertEquals("g560-id", backend.opened.get(1).device.id);
        assertEquals("amr-id", backend.opened.get(backend.opened.size() - 1).device.id);
        assertTrue(failures.isEmpty());
        assertEquals(3, backend.lookups);
        engine.close(); assertTrue(backend.opened.stream().allMatch(stream -> stream.closed));
    }
    @Test public void movesPlayingSoundToNewDefaultAtFirstUnplayedFrame() {
        play(); engine.tick(0);
        FakeStream old = backend.opened.get(0); old.padding = 4; // 6 of its first 10 frames played
        backend.device = G560; engine.tick(100_000_000L);
        FakeStream next = backend.opened.get(1);
        assertTrue(old.closed); assertEquals(24, next.offsets.get(0).intValue());
        assertTrue(next.started); assertEquals("G560 speakers", routes.get(1));
    }
    @Test public void unpluggedOldDeviceStillMovesToNewDefault() {
        play(); engine.tick(0);
        FakeStream old = backend.opened.get(0); old.padding = 5;
        engine.tick(20_000_000L); // remember 5 played frames, then refill
        old.invalid = true; backend.device = G560;
        engine.tick(100_000_000L);
        assertTrue(old.closed); assertTrue(failures.isEmpty());
        assertEquals(20, backend.opened.get(1).offsets.get(0).intValue());
    }
    @Test public void deviceInvalidationBeforeNextPollRechecksDefaultImmediately() {
        play(); engine.tick(0);
        FakeStream old = backend.opened.get(0); old.invalid = true; backend.device = G560;
        engine.tick(1);
        assertTrue(old.closed); assertTrue(failures.isEmpty());
        assertEquals("g560-id", backend.opened.get(1).device.id);
        assertTrue(backend.opened.get(1).started);
    }
    @Test public void closesStreamAfterAllFramesHaveActuallyPlayed() {
        play(); engine.tick(0); FakeStream stream = backend.opened.get(0);
        for (int i = 1; i <= 9; i++) { stream.padding = 0; engine.tick(i); }
        assertFalse(stream.closed); assertEquals(100, stream.written);
        stream.padding = 0; engine.tick(10); assertTrue(stream.closed);
        engine.tick(200_000_000L); assertEquals(1, backend.lookups); // no device polling when idle
    }
    @Test public void cancelBeforeOpeningDoesNotTouchHardwareAndActiveCancelCloses() {
        AudioOutput.Playback pending = play(); pending.stop(); engine.tick(0);
        assertEquals(0, backend.lookups); assertTrue(routes.isEmpty());
        AudioOutput.Playback playing = play(); engine.tick(1); playing.stop(); engine.tick(2);
        assertTrue(backend.opened.get(0).closed);
    }
    @Test public void failureOnSelectedDeviceDoesNotFallBackToAnotherOutput() {
        backend.openFailure = true; play(); engine.tick(0);
        assertEquals(1, backend.lookups); assertEquals(1, backend.openAttempts);
        assertTrue(routes.isEmpty()); assertEquals("AMR unavailable", failures.get(0));
    }
    @Test public void unavailableDefaultStopsOldOutputAndReportsFailure() {
        play(); engine.tick(0); backend.defaultFailure = true; engine.tick(100_000_000L);
        assertTrue(backend.opened.get(0).closed); assertEquals(1, failures.size());
        assertEquals(1, backend.openAttempts);
    }
    @Test public void streamingFailureReleasesDeviceAndNextAlertCanRecover() {
        play(); engine.tick(0); backend.opened.get(0).invalid = true; engine.tick(1);
        assertTrue(backend.opened.get(0).closed); assertEquals(1, failures.size());
        play(); engine.tick(2); assertEquals(2, routes.size());
    }
    @Test public void boundsPendingAlertsAndSimultaneousNativeStreams() {
        for (int i = 0; i < 32; i++) play();
        try { play(); fail("Unbounded queue"); } catch (RejectedExecutionException expected) { }
        for (int i = 0; i < 32; i++) engine.tick(i);
        assertEquals(DefaultAudioEngine.MAX_ACTIVE, backend.opened.stream().filter(s -> !s.closed).count());
        assertTrue(backend.opened.get(0).closed);
        engine.close(); assertTrue(backend.closed);
        assertTrue(backend.opened.stream().allMatch(s -> s.closed));
    }
    @Test public void rejectsPartialFramesBeforeOpeningHardware() {
        try { engine.play(FORMAT, new byte[3], routes::add, failures::add); fail("Incomplete frame"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(0, backend.lookups);
    }

    private static final class FakeBackend implements DefaultAudioEngine.Backend {
        DefaultAudioEngine.Device device = AMR;
        final List<FakeStream> opened = new ArrayList<>();
        int lookups, openAttempts;
        boolean openFailure, defaultFailure, closed;
        public DefaultAudioEngine.Device defaultDevice() throws IOException {
            lookups++; if (defaultFailure) throw new IOException("No default output"); return device;
        }
        public DefaultAudioEngine.Stream open(DefaultAudioEngine.Device selected, AudioFormat format) throws IOException {
            openAttempts++; if (openFailure) throw new IOException("AMR unavailable");
            FakeStream stream = new FakeStream(selected); opened.add(stream); return stream;
        }
        public void close() { closed = true; }
    }
    private static final class FakeStream implements DefaultAudioEngine.Stream {
        final DefaultAudioEngine.Device device;
        final List<Integer> offsets = new ArrayList<>();
        int padding, written;
        boolean closed, started, invalid;
        FakeStream(DefaultAudioEngine.Device device) { this.device = device; }
        public int capacity() { return 10; }
        public int padding() throws IOException { if (invalid) throw new IOException("Device unplugged"); return padding; }
        public void write(byte[] pcm, int offset, int frames) {
            assertFalse(closed); assertTrue(frames + padding <= capacity());
            offsets.add(offset); padding += frames; written += frames;
        }
        public void start() { started = true; }
        public void close() { closed = true; }
    }
}
