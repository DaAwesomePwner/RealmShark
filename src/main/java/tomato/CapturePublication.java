package tomato;

import packets.packetcapture.PacketProcessor;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.TomatoData;

/** Serializes worker ownership with the thread-safe progression mailbox, not live entity collections. */
final class CapturePublication {
    private final TomatoData data;
    private final DiscoveryLog log;
    private PacketProcessor current;
    private boolean stopped;

    CapturePublication(TomatoData data) { this(data, DiscoveryLog.INSTANCE); }
    /** {@code log} receives capture start/stop/transport hooks so recording intervals never span unobserved time. */
    CapturePublication(TomatoData data, DiscoveryLog log) { this.data = data; this.log = log; }

    synchronized void started(PacketProcessor worker) {
        if (current != null) throw new IllegalStateException("Previous capture worker has not terminated");
        current = worker;
        stopped = false;
        log.captureStarted();
        data.captureStarted();
    }

    synchronized void stopRequested(PacketProcessor worker) {
        if (current != worker || stopped) return;
        stopped = true;
        log.captureStopped();
        data.captureStopped();
    }

    /** Called synchronously on the producer, before data from a new/reset transport is dispatched. */
    synchronized void boundary(PacketProcessor worker) {
        if (current == worker && !stopped) { log.captureInterrupted(); data.captureBoundary(); }
    }

    /** Runs before the EDT termination callback, so a blocked EDT cannot keep stale data current. */
    synchronized void terminated(PacketProcessor worker) {
        if (current != worker) return;
        stopRequested(worker);
        current = null;
    }
}
