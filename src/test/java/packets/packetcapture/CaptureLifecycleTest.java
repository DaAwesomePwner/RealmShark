package packets.packetcapture;

import org.junit.Test;
import packets.packetcapture.sniff.Sniffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class CaptureLifecycleTest {
    @Test(timeout = 2000) public void terminalFailureNotifiesTheUiInsteadOfLeavingAZombieProcessor() throws Exception {
        AtomicBoolean callback = new AtomicBoolean();
        PacketProcessor processor = new PacketProcessor() {
            @Override protected Sniffer createSniffer() { throw new IllegalStateException("synthetic initialization failure"); }
        };
        processor.setStoppedListener(() -> callback.set(true));
        processor.start(); processor.join(1000);
        assertFalse(processor.isAlive()); assertTrue(callback.get());
    }
    @Test(timeout = 6000) public void restartsAfterReaderExitAndProcessingFailureButHonorsStop() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch thirdAttempt = new CountDownLatch(1), released = new CountDownLatch(1);
        AtomicBoolean callback = new AtomicBoolean();
        PacketProcessor processor = new PacketProcessor() {
            @Override protected Sniffer createSniffer() {
                return new Sniffer(this) {
                    @Override public void startSniffer() {
                        int count = attempts.incrementAndGet();
                        if (count == 1) return; // Native adapter closes unexpectedly.
                        if (count == 2) throw new IllegalStateException("synthetic processing failure");
                        thirdAttempt.countDown();
                        try { released.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    @Override public void closeSniffers() { if (attempts.get() >= 3) released.countDown(); }
                };
            }
        };
        processor.setStoppedListener(() -> callback.set(true));
        processor.start();
        try { assertTrue(thirdAttempt.await(4, TimeUnit.SECONDS)); }
        finally { processor.stopSniffer(); processor.join(1000); }
        assertFalse(processor.isAlive()); assertTrue(callback.get()); assertEquals(3, attempts.get());
    }

    @Test(timeout = 2000) public void stopDuringRetryWakesImmediately() throws Exception {
        CountDownLatch retry = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        PacketProcessor processor = new PacketProcessor() {
            @Override protected Sniffer createSniffer() {
                return new Sniffer(this) {
                    @Override public void startSniffer() { attempts.incrementAndGet(); }
                };
            }
        };
        processor.setCaptureStatusListener(message -> { if (message.contains("reopening")) retry.countDown(); });
        processor.start();
        try { assertTrue(retry.await(1, TimeUnit.SECONDS)); }
        finally { processor.stopSniffer(); processor.join(500); }
        assertFalse(processor.isAlive()); assertEquals(1, attempts.get());
    }
}
