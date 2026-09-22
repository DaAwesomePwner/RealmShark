package tomato;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.*;
import packets.data.QuestData;
import packets.incoming.QuestFetchResponsePacket;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.sniff.Sniffer;
import packets.packetcapture.sniff.assembly.TcpStreamBuilder;
import packets.packetcapture.sniff.netpackets.Ip4Packet;
import tomato.backend.TomatoPacketCapture;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.TomatoData;
import static org.junit.Assert.*;

/** Real runtime start/stop callbacks with a synthetic adapter loop and no native capture. */
public class CaptureHookIntegrationTest {
    private final Map<Field, Object> original = new LinkedHashMap<>();
    private final List<Worker> workers = new ArrayList<>();
    private TomatoData data;
    private String capturePreference;

    @Before public void isolateRuntime() throws Exception {
        data = new TomatoData();
        capturePreference = util.PropertiesManager.getProperty("sniffer");
        SwingUtilities.invokeAndWait(() -> {
            replace("packetProcessor", null); replace("stoppingCapture", false); replace("restartCapture", false);
            replace("assetsReady", true); replace("setupBusy", false); replace("preview", false);
            replace("capturePublication", new CapturePublication(data));
        });
    }

    @Test public void savedAutoStartWaitsForSuccessfulInitialReadinessAndRetriesPreserveTheChoice() throws Exception {
        Worker worker = new Worker(false); workers.add(worker);
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<PacketProcessor> factory = () -> { created.incrementAndGet(); return worker; };
        util.PropertiesManager.setProperties("sniffer", "T");
        SwingUtilities.invokeAndWait(() -> {
            Tomato.finishAssetSetup(true, false, factory);
            assertEquals(0, created.get()); assertEquals("T", util.PropertiesManager.getProperty("sniffer"));
            Tomato.finishAssetSetup(false, true, factory); // A successful retry is not an implicit start action.
            assertEquals(0, created.get()); assertEquals("T", util.PropertiesManager.getProperty("sniffer"));
            Tomato.finishAssetSetup(true, true, factory); // A subsequent launch with usable assets honors T.
        });
        assertTrue(worker.entered.await(2, TimeUnit.SECONDS)); assertEquals(1, created.get());
        assertSavedCapturePreference("T");
        SwingUtilities.invokeAndWait(() -> Tomato.setCaptureRequested(false, factory));
        assertEquals("F", util.PropertiesManager.getProperty("sniffer"));
        assertSavedCapturePreference("F");
    }

    @Test public void explicitTogglePersistsAndUnexpectedFailureDoesNotEraseSavedAutoStart() throws Exception {
        Worker worker = new Worker(false); workers.add(worker);
        SwingUtilities.invokeAndWait(() -> {
            util.PropertiesManager.setProperties("sniffer", "T");
            Tomato.setCaptureRequested(false, () -> { throw new AssertionError("Stop must not create capture"); });
            Tomato.finishAssetSetup(true, true, () -> { throw new AssertionError("Explicit F must override pending auto-start"); });
            assertEquals("F", util.PropertiesManager.getProperty("sniffer"));
            Tomato.setCaptureRequested(true, () -> worker);
            assertEquals("T", util.PropertiesManager.getProperty("sniffer"));
        });
        assertTrue(worker.entered.await(2, TimeUnit.SECONDS));
        worker.jobs.add(() -> { throw new NoClassDefFoundError("synthetic/CaptureFailure"); });
        worker.join(3000); assertFalse(worker.isAlive()); SwingUtilities.invokeAndWait(() -> {});
        assertEquals("T", util.PropertiesManager.getProperty("sniffer"));
        assertSavedCapturePreference("T");
    }
    private static void assertSavedCapturePreference(String expected) throws Exception {
        assertTrue(util.PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS).isSuccess());
        Properties saved = new Properties();
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(java.nio.file.Paths.get("realmShark.properties"), java.nio.charset.Charset.defaultCharset())) { saved.load(reader); }
        assertEquals(expected, saved.getProperty("sniffer"));
    }
    private void replace(String name, Object value) {
        try { Field field = Tomato.class.getDeclaredField(name); field.setAccessible(true); original.put(field, field.get(null)); field.set(null, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @After public void restoreRuntime() throws Exception {
        SwingUtilities.invokeAndWait(Tomato::stopPacketSniffer);
        for (Worker worker : workers) { worker.allowClose.countDown(); worker.stopSniffer(); worker.join(3000); assertFalse(worker.isAlive()); }
        SwingUtilities.invokeAndWait(() -> {
            try { for (Map.Entry<Field,Object> entry : original.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        util.PropertiesManager.setProperties("sniffer", capturePreference == null ? "" : capturePreference);
    }

    @Test public void startPrecedesWorkerAndStopInvalidatesBeforeBlockedNativeClose() throws Exception {
        data.progression().reset("previous-account", "fixture");
        tomato.backend.data.Entity retained = new tomato.backend.data.Entity(null, 9, 0);
        data.entityList.put(9, retained);
        Worker worker = start(true);
        assertNull(worker.atStart.account); assertTrue(worker.atStart.accepting);
        worker.perform(() -> publish("A", "before-stop"));
        ProgressionData.Scope origin = data.progression().scope();
        SwingUtilities.invokeAndWait(Tomato::stopPacketSniffer);
        assertTrue(worker.closing.await(2, TimeUnit.SECONDS));
        assertTrue(worker.isAlive());
        assertFalse(data.progression().scope().accepting);
        assertFalse(data.progression().snapshot().currentQuests());
        assertFalse(data.progression().quests(origin, quests("late"), 2));
        assertEquals("before-stop", data.progression().snapshot().quests.rows()[0].id);
        assertSame("Mailbox lifecycle must not clear producer-owned entity collections on the EDT", retained, data.entityList.get(9));
    }

    @Test public void automaticAdapterRetryInvalidatesThePreviousScopeBeforeReopening() throws Exception {
        Worker worker = start(false);
        worker.perform(() -> { publish("A", "previous-attempt"); worker.closed = true; });
        assertTrue(worker.reopened.await(3, TimeUnit.SECONDS));
        worker.perform(() -> {
            assertNull(data.progression().scope().account);
            assertFalse(data.progression().snapshot().currentQuests());
            assertEquals("previous-attempt", data.progression().snapshot().quests.rows()[0].id);
        });
    }

    @Test public void restartRequestedDuringCloseWaitsForTerminationThenStartsAFreshScope() throws Exception {
        Worker first = new Worker(true), second = new Worker(false);
        workers.add(first); workers.add(second);
        Queue<Worker> pending = new ArrayDeque<>(Arrays.asList(first, second));
        SwingUtilities.invokeAndWait(() -> Tomato.startPacketSniffer(pending::remove));
        assertTrue(first.entered.await(2, TimeUnit.SECONDS)); first.perform(() -> publish("A", "old"));
        SwingUtilities.invokeAndWait(() -> { Tomato.stopPacketSniffer(); Tomato.startPacketSniffer(pending::remove); });
        assertTrue(first.closing.await(2, TimeUnit.SECONDS));
        assertEquals(Thread.State.NEW, second.getState()); assertFalse(data.progression().scope().accepting);
        first.allowClose.countDown(); first.join(3000); assertFalse(first.isAlive());
        assertTrue(second.entered.await(2, TimeUnit.SECONDS));
        assertTrue(second.atStart.accepting); assertNull(second.atStart.account);
        assertFalse(data.progression().snapshot().currentQuests());
    }

    @Test public void unexpectedTerminationInvalidatesBeforeTheBlockedEdtCanHandleIt() throws Exception {
        Worker worker = start(false); worker.perform(() -> publish("A", "retained"));
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> { blocked.countDown(); await(release); });
        try {
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            worker.jobs.add(() -> { throw new NoClassDefFoundError("synthetic/CaptureFailure"); });
            worker.join(3000); assertFalse(worker.isAlive());
            assertFalse(data.progression().scope().accepting);
            assertFalse(data.progression().snapshot().currentQuests());
        } finally { release.countDown(); }
        SwingUtilities.invokeAndWait(() -> assertFalse(Tomato.isCaptureRunning()));
    }

    @Test public void retiredTerminationAndBoundaryCallbacksCannotInvalidateTheReplacement() throws Exception {
        Worker first = start(false); first.perform(() -> publish("A", "first"));
        SwingUtilities.invokeAndWait(Tomato::stopPacketSniffer);
        first.join(3000); assertFalse(first.isAlive()); SwingUtilities.invokeAndWait(() -> {});
        Worker second = start(false); second.perform(() -> publish("B", "second"));
        ProgressionData.Scope current = data.progression().scope();
        first.boundaryCallback.run(); first.stoppedCallback.run(); first.resetIncoming(); first.resetOutgoing();
        SwingUtilities.invokeAndWait(() -> assertTrue(Tomato.isCaptureRunning()));
        assertSame(current, data.progression().scope());
        assertTrue(data.progression().snapshot().currentQuests());
        assertEquals("second", data.progression().snapshot().quests.rows()[0].id);
    }

    @Test public void transportResetsInvalidateBeforeNewFlowPayloadDispatchOnTheProducer() throws Exception {
        Worker worker = start(false);
        worker.perform(() -> {
            publish("A", "old-flow");
            ProgressionData.Scope old = data.progression().scope();
            TomatoPacketCapture capture = new TomatoPacketCapture(data);
            TcpStreamBuilder stream = new TcpStreamBuilder(worker::resetIncoming, (bytes, address) -> {
                assertSame(worker, Thread.currentThread());
                assertNull(data.progression().scope().account);
                assertFalse(data.progression().quests(old, quests("late-old"), 3));
                QuestFetchResponsePacket fetched = new QuestFetchResponsePacket(); fetched.quests = quests("unverified-new");
                capture.packetCapture(fetched);
            });
            stream.streamBuilder(tcp(10, 2, false, 50000));
            stream.streamBuilder(tcp(11, 24, true, 50000));
            assertEquals("unverified-new", data.progression().snapshot().quests.rows()[0].id);
            assertFalse(data.progression().snapshot().currentQuests());
            publish("B", "verified-new");
            // A changed tuple without a captured SYN still resets before delivering its bytes.
            stream.streamBuilder(tcp(30, 24, true, 50001));
            assertNull(data.progression().scope().account);
            publish("B", "before-outgoing-reset"); worker.resetOutgoing();
            assertFalse(data.progression().snapshot().currentQuests());
        });
    }

    private Worker start(boolean blockClose) throws Exception {
        Worker worker = new Worker(blockClose); workers.add(worker);
        SwingUtilities.invokeAndWait(() -> Tomato.startPacketSniffer(() -> worker));
        assertTrue(worker.entered.await(2, TimeUnit.SECONDS)); return worker;
    }
    private void publish(String account, String id) {
        data.progression().reset(account, "fixture verified account");
        QuestFetchResponsePacket packet = new QuestFetchResponsePacket(); packet.quests = quests(id);
        new TomatoPacketCapture(data).packetCapture(packet);
    }
    private static QuestData[] quests(String id) { QuestData q = new QuestData(); q.id = id; return new QuestData[]{q}; }
    private static packets.packetcapture.sniff.netpackets.TcpPacket tcp(int sequence, int flags, boolean payload, int clientPort) {
        ByteBuffer ip = ByteBuffer.allocate(payload ? 41 : 40);
        ip.put(0, (byte)0x45); ip.putShort(2, (short)ip.capacity()); ip.put(9, (byte)6);
        ip.put(12, (byte)10); ip.put(16, (byte)11); ip.putShort(20, (short)2050); ip.putShort(22, (short)clientPort);
        ip.putInt(24, sequence); ip.put(32, (byte)0x50); ip.put(33, (byte)flags);
        return new Ip4Packet(ip.array(), null).getNewTcpPacket();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for fixture release"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private final class Worker extends PacketProcessor {
        final BlockingQueue<Runnable> jobs = new LinkedBlockingQueue<>();
        final CountDownLatch entered = new CountDownLatch(1), closing = new CountDownLatch(1), reopened = new CountDownLatch(1), allowClose;
        int attempts;
        volatile boolean closed;
        ProgressionData.Scope atStart;
        Runnable stoppedCallback, boundaryCallback;
        Worker(boolean blockClose) { allowClose = new CountDownLatch(blockClose ? 1 : 0); }
        @Override public synchronized void start() { atStart = data.progression().scope(); super.start(); }
        @Override public void setStoppedListener(Runnable listener) { stoppedCallback = listener; super.setStoppedListener(listener); }
        @Override public void setBoundaryListener(Runnable listener) { boundaryCallback = listener; super.setBoundaryListener(listener); }
        @Override protected Sniffer createSniffer() {
            closed = false;
            int attempt = ++attempts;
            return new Sniffer(this) {
                @Override public void startSniffer() {
                    assertFalse(SwingUtilities.isEventDispatchThread()); entered.countDown();
                    if (attempt > 1) reopened.countDown();
                    try { while (!closed) jobs.take().run(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                @Override public void closeSniffers() { closing.countDown(); await(allowClose); closed = true; jobs.add(() -> {}); }
            };
        }
        void perform(Runnable action) throws Exception { FutureTask<Void> task = new FutureTask<>(action, null); jobs.add(task); task.get(3, TimeUnit.SECONDS); }
    }
}
