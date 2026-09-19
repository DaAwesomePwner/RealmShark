package tomato.realmshark.audio;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Single-threaded streaming lifecycle, with a bounded thread-safe submission queue. */
final class DefaultAudioEngine implements AutoCloseable {
    static final int MAX_ACTIVE = 16;
    private static final long DEVICE_POLL_NS = 100_000_000L;
    interface Backend extends AutoCloseable {
        Device defaultDevice() throws Exception;
        Stream open(Device device, AudioFormat format) throws Exception;
        void close();
    }
    static final class Device {
        final String id, name;
        Device(String id, String name) { this.id = id; this.name = name; }
    }
    interface Stream extends AutoCloseable {
        int capacity();
        int padding() throws Exception;
        void write(byte[] pcm, int offset, int frames) throws Exception;
        void start() throws Exception;
        void close();
    }
    private final Backend backend;
    private final ArrayBlockingQueue<Request> pending = new ArrayBlockingQueue<>(32);
    private final List<Request> active = new ArrayList<>();
    private long lastDevicePoll;

    DefaultAudioEngine(Backend backend) { this.backend = backend; }

    AudioOutput.Playback play(AudioFormat format, byte[] pcm, Consumer<String> route, Consumer<String> failure) {
        if (format.getFrameSize() <= 0 || pcm.length == 0 || pcm.length % format.getFrameSize() != 0)
            throw new IllegalArgumentException("Audio must contain complete PCM frames");
        Request request = new Request(format, pcm, route, failure);
        if (!pending.offer(request)) throw new RejectedExecutionException("Audio is busy; this alert was skipped");
        return request;
    }

    /** Called only on the native audio thread; tests drive it with a fake backend and clock. */
    void tick(long now) {
        for (Iterator<Request> it = active.iterator(); it.hasNext();) {
            Request request = it.next();
            if (request.stopped) { request.closeStream(); it.remove(); }
        }
        Request next = pending.poll();
        while (next != null && next.stopped) next = pending.poll();
        Device current = null;
        if (next != null || (!active.isEmpty() && now - lastDevicePoll >= DEVICE_POLL_NS)) {
            try {
                current = backend.defaultDevice();
                lastDevicePoll = now;
            } catch (Exception | LinkageError e) {
                if (next != null) next.fail(e);
                for (Request request : active) request.fail(e);
                active.clear();
                return;
            }
        }
        for (Iterator<Request> it = active.iterator(); it.hasNext();) {
            Request request = it.next();
            try {
                if (current != null && !current.id.equals(request.device.id)) {
                    // Resume at the first unplayed frame, including audio queued on the old endpoint.
                    try {
                        int padding = request.stream.padding();
                        request.playedCursor = Math.max(0, request.cursor - padding * request.format.getFrameSize());
                    } catch (Exception ignored) {
                        // An unplugged endpoint may already be invalid; use the last observed progress.
                    }
                    request.cursor = request.playedCursor;
                    request.closeStream();
                    open(request, current);
                }
                if (!pump(request)) { request.closeStream(); it.remove(); }
            } catch (Exception | LinkageError e) {
                // Device removal can invalidate the stream before the next scheduled default check.
                try {
                    Device replacement = backend.defaultDevice();
                    if (replacement.id.equals(request.device.id)) throw e;
                    request.cursor = request.playedCursor;
                    request.closeStream();
                    open(request, replacement);
                } catch (Exception | LinkageError retryError) { request.fail(retryError); it.remove(); }
            }
        }
        if (next != null && !next.stopped) {
            if (active.size() == MAX_ACTIVE) active.remove(0).stopAndClose();
            try { open(next, current); active.add(next); }
            catch (Exception | LinkageError e) { next.fail(e); }
        }
    }

    private void open(Request request, Device device) throws Exception {
        request.device = device;
        request.stream = backend.open(device, request.format);
        if (request.stream.capacity() <= 0) throw new IllegalStateException("The audio output has no buffer");
        if (!request.stopped && pump(request)) {
            request.stream.start();
            if (!request.stopped) emit(request.route, device.name);
        }
    }
    private boolean pump(Request request) throws Exception {
        int padding = request.stream.padding();
        request.playedCursor = Math.max(0, request.cursor - padding * request.format.getFrameSize());
        if (request.cursor == request.pcm.length && padding == 0) return false;
        int frames = Math.min(request.stream.capacity() - padding,
                (request.pcm.length - request.cursor) / request.format.getFrameSize());
        if (frames > 0) {
            request.stream.write(request.pcm, request.cursor, frames);
            request.cursor += frames * request.format.getFrameSize();
        }
        return true;
    }
    @Override public void close() {
        for (Request request : active) request.stopAndClose();
        active.clear();
        Request request;
        while ((request = pending.poll()) != null) request.stop();
        backend.close();
    }
    private static void emit(Consumer<String> callback, String message) {
        // A UI listener must not kill the audio worker or leak its native streams.
        try { callback.accept(message); } catch (RuntimeException ignored) { }
    }
    private static final class Request implements AudioOutput.Playback {
        final AudioFormat format;
        final byte[] pcm;
        final Consumer<String> route, failure;
        volatile boolean stopped;
        Device device;
        Stream stream;
        int cursor, playedCursor;
        Request(AudioFormat format, byte[] pcm, Consumer<String> route, Consumer<String> failure) {
            this.format = format; this.pcm = pcm; this.route = route; this.failure = failure;
        }
        @Override public void stop() { stopped = true; }
        void stopAndClose() { stop(); closeStream(); }
        void closeStream() { if (stream != null) { stream.close(); stream = null; } }
        void fail(Throwable error) {
            stopAndClose();
            emit(failure, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }
}
