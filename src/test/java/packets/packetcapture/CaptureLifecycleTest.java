package packets.packetcapture;

import org.junit.Test;
import packets.packetcapture.sniff.Sniffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class CaptureLifecycleTest {
    @Test(timeout = 2000) public void missingClassRequiresAnAppRestartInsteadOfLoopingInTheBrokenJvm() throws Exception {
        AtomicBoolean callback = new AtomicBoolean();
        PacketProcessor processor = new PacketProcessor() {
            @Override protected Sniffer createSniffer() {
                NoClassDefFoundError failure = new NoClassDefFoundError("packets/data/TradeItemData");
                failure.initCause(new ClassNotFoundException("packets.data.TradeItemData"));
                throw failure;
            }
        };
        processor.setStoppedListener(() -> callback.set(true));
        processor.start(); processor.join(1000);
        assertFalse(processor.isAlive()); assertTrue(callback.get());
        assertTrue(processor.getStopReason().contains("Restart RealmShark"));
        assertTrue(processor.getStopReason().contains("packets.data.TradeItemData"));
        assertTrue(processor.getStopReason().contains("Restarting capture alone cannot repair"));
    }

    @Test(timeout = 6000) public void initializationAndCleanupFailuresStillRetryWithABrokenStatusListener() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch retrySucceeded = new CountDownLatch(1), released = new CountDownLatch(1);
        PacketProcessor processor = new PacketProcessor() {
            @Override protected Sniffer createSniffer() {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("initialization failure");
                return new Sniffer(this) {
                    @Override public void startSniffer() {
                        if (attempts.get() == 2) return;
                        retrySucceeded.countDown();
                        try { released.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    @Override public void closeSniffers() {
                        released.countDown();
                        throw new IllegalStateException("cleanup failure");
                    }
                };
            }
        };
        processor.setCaptureStatusListener(message -> { throw new IllegalStateException("status listener failure"); });
        processor.start();
        try { assertTrue(retrySucceeded.await(4, TimeUnit.SECONDS)); }
        finally { processor.stopSniffer(); processor.join(500); }
        assertFalse(processor.isAlive()); assertEquals(3, attempts.get());
    }

    @Test public void missingClassDiagnosticNeverCopiesArbitraryExceptionMessages() {
        assertEquals("packets.data.TradeItemData", CaptureDiagnostics.missingClassName(new NoClassDefFoundError("packets/data/TradeItemData")));
        assertEquals("", CaptureDiagnostics.missingClassName(new ClassNotFoundException("sensitive packet contents: secret")));
        assertEquals("", CaptureDiagnostics.missingClassName(new IllegalStateException("accountName")));
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
