package packets.packetcapture.sniff;

import java.util.ArrayDeque;
import packets.packetcapture.sniff.netpackets.RawPacket;

/** One winning adapter and a condition-guarded queue shared with capture callbacks. */
final class CaptureQueue<S> {
    private final ArrayDeque<RawPacket> packets = new ArrayDeque<>();
    private S owner;
    private boolean stopped;

    synchronized boolean offer(S source, RawPacket packet) {
        if (stopped || (owner != null && owner != source)) return false;
        if (owner == null) owner = source;
        packets.addLast(packet);
        notifyAll();
        return true;
    }

    synchronized S awaitOwner() throws InterruptedException {
        while (owner == null && !stopped) wait();
        return owner;
    }

    synchronized RawPacket take() throws InterruptedException {
        while (packets.isEmpty() && !stopped) wait();
        return stopped ? null : packets.removeFirst();
    }

    synchronized S owner() { return owner; }
    synchronized boolean isStopped() { return stopped; }
    synchronized void stop() {
        stopped = true;
        packets.clear();
        notifyAll();
    }
}
