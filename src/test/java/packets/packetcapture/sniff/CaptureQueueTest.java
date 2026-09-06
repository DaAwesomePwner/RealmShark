package packets.packetcapture.sniff;

import org.junit.Test;
import packets.packetcapture.sniff.netpackets.RawPacket;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class CaptureQueueTest {
    @Test(timeout = 3000) public void aPacketReceivedBeforeWaitingIsNotLostAndOtherAdaptersAreIgnored() throws Exception {
        CaptureQueue<Object> queue = new CaptureQueue<>();
        Object vpn = new Object(), ethernet = new Object();
        RawPacket first = CaptureTestPackets.packet(12, false, 2050, 55000);
        assertTrue(queue.offer(vpn, first));
        assertFalse(queue.offer(ethernet, first));
        assertSame(vpn, queue.awaitOwner());
        assertSame(first, queue.take());
    }

    @Test(timeout = 3000) public void stoppingWakesBothAdapterAndPacketWaiters() throws Exception {
        CaptureQueue<Object> queue = new CaptureQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> owner = executor.submit(queue::awaitOwner);
            Future<RawPacket> packet = executor.submit(queue::take);
            queue.stop();
            assertNull(owner.get(1, TimeUnit.SECONDS));
            assertNull(packet.get(1, TimeUnit.SECONDS));
            assertFalse(queue.offer(new Object(), CaptureTestPackets.packet(12, false, 2050, 55000)));
        } finally { executor.shutdownNow(); }
    }

    @Test(timeout = 3000) public void concurrentAdaptersCannotChangeTheWinner() throws Exception {
        CaptureQueue<Object> queue = new CaptureQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Object a = new Object(), b = new Object();
        RawPacket packet = CaptureTestPackets.packet(12, false, 2050, 55000);
        try {
            Future<Boolean> first = executor.submit(() -> { start.await(); return queue.offer(a, packet); });
            Future<Boolean> second = executor.submit(() -> { start.await(); return queue.offer(b, packet); });
            start.countDown();
            boolean aWon = first.get(), bWon = second.get();
            assertTrue(aWon ^ bWon);
            assertSame(aWon ? a : b, queue.owner());
            assertSame(packet, queue.take());
        } finally { executor.shutdownNow(); }
    }
}
