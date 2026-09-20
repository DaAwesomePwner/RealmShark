package tomato.realmshark;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

public class LootDeliveryTest {
    @Test public void boundedFifoDropsNewestAndOwnsBytesWithOneSender() throws Exception {
        ControlledLootTransport transport = new ControlledLootTransport(true, false);
        AtomicInteger creations = new AtomicInteger();
        LootDelivery delivery = new LootDelivery(() -> { creations.incrementAndGet(); return transport; }, 2, true, false);
        Thread producer = Thread.currentThread();
        try {
            assertTrue(offer(delivery, "first"));
            assertTrue(transport.connectEntered.await(2, TimeUnit.SECONDS));
            byte[] mutable = bytes("second");
            assertTrue(delivery.offer(mutable, delivery.generation()));
            mutable[0] = 'X';
            assertTrue(offer(delivery, "third"));
            assertFalse(offer(delivery, "overflow"));
            assertEquals(2, delivery.snapshot().waiting);
            assertEquals(1, delivery.snapshot().active);
            assertEquals(1, delivery.snapshot().dropped);
            assertTrue(delivery.snapshot().lastError.contains("queue full"));
            transport.release();
            await(() -> delivery.snapshot().sentToSocket == 3);
            assertEquals(3, delivery.snapshot().queued);
            assertEquals(0, delivery.snapshot().uncertain);
            assertEquals(3, transport.payloads.size());
            assertArrayEquals(bytes("first"), transport.payloads.get(0));
            assertArrayEquals(bytes("second"), transport.payloads.get(1));
            assertArrayEquals(bytes("third"), transport.payloads.get(2));
            assertEquals(1, creations.get());
        } finally { stop(delivery, transport); }
        Thread sender = transport.ioThreads.get(0);
        assertNotSame(producer, sender);
        assertTrue(sender.isDaemon());
        for (Thread thread : transport.ioThreads) assertSame(sender, thread);
        assertFalse(transport.ioOnEdt);
    }

    @Test public void disableGenerationCancelsQueuedAndLateConnectEvenAfterReenable() throws Exception {
        ControlledLootTransport late = new ControlledLootTransport(true, false);
        late.ignoreConnectInterrupt = true;
        ControlledLootTransport next = new ControlledLootTransport(false, false);
        AtomicInteger created = new AtomicInteger();
        LootDelivery delivery = new LootDelivery(() -> created.getAndIncrement() == 0 ? late : next, 3, true, false);
        try {
            long old = delivery.generation();
            assertTrue(offer(delivery, "late connect"));
            assertTrue(late.connectEntered.await(2, TimeUnit.SECONDS));
            assertTrue(offer(delivery, "queued"));
            delivery.setEnabled(false);
            assertEquals(2, delivery.snapshot().dropped);
            assertEquals(0, delivery.snapshot().waiting);
            assertFalse(delivery.snapshot().enabled);
            delivery.setEnabled(true);
            assertFalse(delivery.offer(bytes("old producer"), old));
            assertTrue(offer(delivery, "new generation"));
            late.release();
            await(() -> delivery.snapshot().sentToSocket == 1);
            assertTrue(late.payloads.isEmpty());
            assertEquals(3, delivery.snapshot().dropped);
            assertEquals(0, delivery.snapshot().uncertain);
            assertEquals(1, next.payloads.size());
            assertArrayEquals(bytes("new generation"), next.payloads.get(0));
            assertTrue(late.closed.await(2, TimeUnit.SECONDS));
        } finally { late.release(); stop(delivery, next); }
    }

    @Test public void connectAndAmbiguousSendFailuresDoNotPoisonWorkerOrRetryPayloads() throws Exception {
        ControlledLootTransport connectFailure = new ControlledLootTransport(false, false); connectFailure.failConnect = true;
        ControlledLootTransport writeFailure = new ControlledLootTransport(false, false); writeFailure.failSend = true;
        ControlledLootTransport good = new ControlledLootTransport(false, false);
        ControlledLootTransport[] attempts = {connectFailure, writeFailure, good};
        AtomicInteger created = new AtomicInteger();
        LootDelivery delivery = new LootDelivery(() -> attempts[created.getAndIncrement()], 4, true, false);
        try {
            assertTrue(offer(delivery, "no connection"));
            await(() -> delivery.snapshot().dropped == 1);
            assertTrue(offer(delivery, "ambiguous"));
            await(() -> delivery.snapshot().uncertain == 1);
            assertTrue(delivery.snapshot().lastError.contains("ambiguous write"));
            assertTrue(offer(delivery, "later attempt"));
            await(() -> delivery.snapshot().sentToSocket == 1);
            assertEquals(3, created.get());
            assertEquals(1, delivery.snapshot().dropped);
            assertEquals(1, delivery.snapshot().uncertain);
            assertTrue(connectFailure.payloads.isEmpty());
            assertEquals(1, writeFailure.payloads.size());
            assertArrayEquals(bytes("ambiguous"), writeFailure.payloads.get(0));
            assertEquals(1, good.payloads.size());
            assertArrayEquals(bytes("later attempt"), good.payloads.get(0));
        } finally { stop(delivery, good); }
    }

    @Test public void disableDuringSendIsUncertainExactlyOnceAndShutdownWaitIsBounded() throws Exception {
        ControlledLootTransport transport = new ControlledLootTransport(false, true);
        transport.ignoreSendInterrupt = true;
        LootDelivery delivery = new LootDelivery(() -> transport, 2, true, false);
        try {
            offer(delivery, "in flight");
            assertTrue(transport.sendEntered.await(2, TimeUnit.SECONDS));
            offer(delivery, "unsent");
            delivery.setEnabled(false);
            assertEquals(1, delivery.snapshot().uncertain);
            assertEquals(1, delivery.snapshot().dropped);
            assertEquals(0, delivery.snapshot().sentToSocket);
            delivery.close();
            assertFalse("Does not join indefinitely on an uncooperative operation", delivery.awaitStopped(20));
            transport.release();
            assertTrue(delivery.awaitStopped(2000));
            assertEquals(1, delivery.snapshot().uncertain);
            assertEquals(1, transport.payloads.size());
        } finally { stop(delivery, transport); }
    }

    @Test public void runtimeFactoryAndCloseFailuresRemainObservableAndWorkerRecovers() throws Exception {
        ControlledLootTransport bad = new ControlledLootTransport(false, false);
        bad.failConnect = true; bad.failClose = true;
        ControlledLootTransport good = new ControlledLootTransport(false, false);
        AtomicInteger created = new AtomicInteger();
        LootDelivery delivery = new LootDelivery(() -> {
            int attempt = created.getAndIncrement();
            if (attempt == 0) throw new IllegalStateException("Controlled factory failure");
            return attempt == 1 ? bad : good;
        }, 2, true, false);
        try {
            offer(delivery, "factory failure");
            await(() -> delivery.snapshot().dropped == 1);
            assertTrue(delivery.snapshot().lastError.contains("factory failure"));
            offer(delivery, "connect and close failure");
            await(() -> delivery.snapshot().lastError.contains("close failure"));
            assertEquals(2, delivery.snapshot().dropped);
            offer(delivery, "recovery");
            await(() -> delivery.snapshot().sentToSocket == 1);
            assertEquals(3, created.get());
            assertEquals(1, good.payloads.size());
        } finally { stop(delivery, good); }
    }

    @Test public void previewOptOutTicksAndOversizePayloadsNeverConstructTransport() throws Exception {
        AtomicInteger created = new AtomicInteger();
        for (boolean preview : new boolean[]{false, true}) {
            LootDelivery delivery = new LootDelivery(() -> { created.incrementAndGet(); throw new AssertionError("No I/O"); },
                2, preview, preview);
            SendLoot.Session session = new SendLoot.Session(delivery);
            try {
                for (int i = 0; i < 10; i++) session.beginLootTick(i);
                assertFalse(offer(delivery, "disabled"));
                if (preview) {
                    session.setEnabled(true);
                    assertFalse(offer(delivery, "still preview"));
                    assertTrue(delivery.snapshot().state.contains("Preview"));
                }
                assertEquals(0, created.get());
                assertTrue(delivery.awaitStopped(1));
            } finally { session.close(); }
        }
        LootDelivery delivery = new LootDelivery(() -> { throw new AssertionError("Oversize must not connect"); }, 1, true, false);
        try {
            assertFalse(delivery.offer(new byte[LootDelivery.MAX_PAYLOAD_BYTES + 1], delivery.generation()));
            assertEquals(1, delivery.snapshot().dropped);
            assertTrue(delivery.awaitStopped(1));
        } finally { delivery.close(); }
    }

    @Test public void definitePreEnqueueRaceReconnectsOnceWithoutDuplicateOrUncertainCount() throws Exception {
        ControlledLootTransport rejected = new ControlledLootTransport(false, false); rejected.rejectBeforeEnqueue = true;
        ControlledLootTransport good = new ControlledLootTransport(false, false);
        AtomicInteger created = new AtomicInteger();
        LootDelivery delivery = new LootDelivery(() -> created.getAndIncrement() == 0 ? rejected : good, 2, true, false);
        try {
            offer(delivery, "safe retry");
            await(() -> delivery.snapshot().sentToSocket == 1);
            assertEquals(2, created.get());
            assertTrue(rejected.payloads.isEmpty());
            assertEquals(1, good.payloads.size());
            assertArrayEquals(bytes("safe retry"), good.payloads.get(0));
            assertEquals(0, delivery.snapshot().uncertain);
            assertEquals(0, delivery.snapshot().dropped);
        } finally { stop(delivery, good); }
    }

    @Test public void repeatedDefiniteRejectionStopsAfterOneReconnectAndCountsDropped() throws Exception {
        AtomicInteger created = new AtomicInteger();
        LootDelivery delivery = new LootDelivery(() -> {
            created.incrementAndGet();
            ControlledLootTransport transport = new ControlledLootTransport(false, false);
            transport.rejectBeforeEnqueue = true; return transport;
        }, 2, true, false);
        try {
            offer(delivery, "rejected twice");
            await(() -> delivery.snapshot().dropped == 1);
            assertEquals(2, created.get());
            assertEquals(0, delivery.snapshot().uncertain);
            assertEquals(0, delivery.snapshot().sentToSocket);
        } finally { delivery.close(); assertTrue(delivery.awaitStopped(2000)); }
    }

    @Test public void optOutDuringSafeReconnectCancelsTheStillUnsentPayload() throws Exception {
        ControlledLootTransport rejected = new ControlledLootTransport(false, false); rejected.rejectBeforeEnqueue = true;
        ControlledLootTransport late = new ControlledLootTransport(true, false); late.ignoreConnectInterrupt = true;
        AtomicInteger created = new AtomicInteger();
        LootDelivery delivery = new LootDelivery(() -> created.getAndIncrement() == 0 ? rejected : late, 2, true, false);
        try {
            offer(delivery, "cancelled safe retry");
            assertTrue(late.connectEntered.await(2, TimeUnit.SECONDS));
            delivery.setEnabled(false);
            assertEquals(1, delivery.snapshot().dropped);
            assertEquals(0, delivery.snapshot().uncertain);
            late.release();
            assertTrue(late.closed.await(2, TimeUnit.SECONDS));
            assertTrue(rejected.payloads.isEmpty()); assertTrue(late.payloads.isEmpty());
            assertEquals(2, created.get());
        } finally { stop(delivery, late); }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static boolean offer(LootDelivery delivery, String text) { return delivery.offer(bytes(text), delivery.generation()); }
    private static void stop(LootDelivery delivery, ControlledLootTransport transport) throws Exception {
        transport.release(); delivery.close(); assertTrue("Owned sender stops", delivery.awaitStopped(2000));
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Expected sender state within deadline", condition.getAsBoolean());
    }
}
