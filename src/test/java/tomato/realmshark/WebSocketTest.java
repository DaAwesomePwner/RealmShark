package tomato.realmshark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Role;
import org.java_websocket.handshake.HandshakeImpl1Client;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the production adapter/decoder through real in-memory handshakes; no connect/listen I/O. */
public class WebSocketTest {
    @Test public void productionClientAndEngineCopyUseFiniteFrameLimit() throws Exception {
        try (MemoryConnection connection = connected()) {
            assertEquals(LootWebSocketDraft.MAX_MESSAGE_BYTES, ((Draft_6455)connection.client.getDraft()).getMaxFrameSize());
            assertEquals(LootWebSocketDraft.MAX_MESSAGE_BYTES, ((Draft_6455)connection.engine.getDraft()).getMaxFrameSize());
            // Advertise an oversized frame without allocating/providing its payload.
            ByteBuffer header = ByteBuffer.allocate(10).put((byte)0x82).put((byte)127)
                .putLong(LootWebSocketDraft.MAX_MESSAGE_BYTES + 1L);
            header.flip(); connection.engine.decode(header);
            assertFalse(connection.isOpen());
            assertTrue(connection.lastError(), connection.lastError().contains("inbound frame rejected"));
            assertTrue("Overflow aborts without queuing a close response", connection.engine.outQueue.isEmpty());
        }
    }

    @Test public void unfinishedFragmentBytesAndZeroLengthFragmentCountAreBothBounded() throws Exception {
        try (MemoryConnection connection = connected()) {
            connection.engine.decode(frame(2, false, LootWebSocketDraft.MAX_MESSAGE_BYTES / 2));
            connection.engine.decode(frame(0, false, LootWebSocketDraft.MAX_MESSAGE_BYTES / 2));
            assertTrue(connection.isOpen());
            // There is deliberately no final fragment: stock 1.5.3 omits this intermediate check.
            connection.engine.decode(frame(0, false, 1));
            assertFalse(connection.isOpen());
            assertTrue(connection.lastError().contains("fragmented message"));
        }
        try (MemoryConnection connection = connected()) {
            connection.engine.decode(frame(2, false, 0));
            for (int i = 1; i < LootWebSocketDraft.MAX_FRAGMENTS; i++) connection.engine.decode(frame(0, false, 0));
            assertTrue(connection.isOpen());
            connection.engine.decode(frame(0, false, 0));
            assertFalse(connection.isOpen());
            assertTrue(connection.lastError().contains("byte/count limit"));
        }
    }

    @Test public void completedFragmentsResetBudgetAndHandshakeBytesAreBounded() throws Exception {
        try (MemoryConnection connection = connected()) {
            for (int message = 0; message < 3; message++) {
                connection.engine.decode(frame(2, false, LootWebSocketDraft.MAX_MESSAGE_BYTES / 2));
                connection.engine.decode(frame(0, true, LootWebSocketDraft.MAX_MESSAGE_BYTES / 2));
                assertTrue(connection.isOpen());
            }
        }
        try (MemoryConnection connection = new MemoryConnection()) {
            connection.prepareHandshake();
            byte[] hugeHeader = new byte[LootWebSocketDraft.MAX_HANDSHAKE_BYTES + 1];
            java.util.Arrays.fill(hugeHeader, (byte)'a');
            connection.engine.decode(ByteBuffer.wrap(hugeHeader));
            assertTrue(connection.engine.isClosed());
            assertTrue(connection.lastError().contains("handshake exceeds"));
        }
    }

    @Test public void idlePongPressureCountsTheDequeuedButBlockedSocketWrite() throws Exception {
        try (MemoryConnection connection = connected()) {
            connection.engine.decode(frame(9, true, 125));
            ByteBuffer inFlight = connection.engine.outQueue.poll(1, TimeUnit.SECONDS);
            assertNotNull(inFlight);
            // Simulate the library writer taking a frame then blocking inside OutputStream.write.
            for (int i = 1; i < WebSocket.MAX_PENDING_PONGS; i++) connection.engine.decode(frame(9, true, 125));
            assertTrue(connection.isOpen());
            assertEquals(WebSocket.MAX_PENDING_PONGS - 1, connection.engine.outQueue.size());
            connection.engine.decode(frame(9, true, 125));
            assertFalse(connection.isOpen());
            assertTrue(connection.lastError().contains("control-output backpressure"));
            for (int i = 0; i < 100; i++) connection.engine.decode(frame(9, true, 125));
            assertEquals("Closed peer cannot grow the output queue", WebSocket.MAX_PENDING_PONGS - 1,
                connection.engine.outQueue.size());
            for (ByteBuffer pong : connection.engine.outQueue) assertTrue(pong.remaining() <= 131);
        }
    }

    @Test public void completedPongWritesReleaseCapacityWithoutCountingAsLootWrites() throws Exception {
        ExecutorService sender = Executors.newSingleThreadExecutor();
        try (MemoryConnection connection = connected()) {
            for (int i = 0; i < WebSocket.MAX_PENDING_PONGS * 4; i++) {
                connection.engine.decode(frame(9, true, 8));
                ByteBuffer pong = connection.engine.outQueue.poll(1, TimeUnit.SECONDS);
                assertNotNull(pong);
                assertEquals(10, pong.get(0) & 0x0f);
                connection.output.write(pong.array(), 0, pong.limit());
            }
            assertTrue(connection.isOpen());
            assertTrue(connection.engine.outQueue.isEmpty());
            Future<?> send = sender.submit(() -> { connection.send(bytes("loot"), 2000); return null; });
            ByteBuffer binary = connection.engine.outQueue.poll(1, TimeUnit.SECONDS);
            assertNotNull(binary); assertEquals(2, binary.get(0) & 0x0f);
            connection.engine.decode(frame(9, true, 8));
            ByteBuffer pong = connection.engine.outQueue.poll(1, TimeUnit.SECONDS);
            assertNotNull(pong); connection.output.write(pong.array(), 0, pong.limit());
            try { send.get(100, TimeUnit.MILLISECONDS); fail("A pong must not complete a loot write"); }
            catch (TimeoutException expected) { }
            connection.output.write(binary.array(), 0, binary.limit());
            send.get(2, TimeUnit.SECONDS);
        } finally { sender.shutdownNow(); }
    }

    @Test public void oneBlockedIdlePongHasAFiniteDeadlineEvenWithoutMoreLootOrPings() throws Exception {
        try (MemoryConnection connection = connected()) {
            connection.engine.decode(frame(9, true, 8));
            assertNotNull(connection.engine.outQueue.poll());
            connection.adapter.checkHealth(System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(WebSocket.CONTROL_TIMEOUT_MILLIS + 1L));
            assertFalse(connection.isOpen());
            assertTrue(connection.lastError().contains("control-output write timed out"));
        }
    }

    @Test public void preEnqueueCloseIsDefiniteButFailureAfterEnqueueRemainsAmbiguous() throws Exception {
        try (MemoryConnection connection = connected()) {
            connection.engine.eot();
            try { connection.send(bytes("never queued"), 100); fail("Closed connection accepted send"); }
            catch (LootTransport.NotSentException expected) { assertTrue(connection.engine.outQueue.isEmpty()); }
        }
        ExecutorService sender = Executors.newSingleThreadExecutor();
        try (MemoryConnection connection = connected()) {
            Future<?> send = sender.submit(() -> { connection.send(bytes("possibly partial"), 2000); return null; });
            ByteBuffer binary = connection.engine.outQueue.poll(1, TimeUnit.SECONDS);
            assertNotNull(binary);
            assertEquals(2, binary.get(0) & 0x0f);
            OutputStream failedWrite = connection.adapter.observeWrites(new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("Partial socket write"); }
            });
            try { failedWrite.write(binary.array(), 0, binary.limit()); fail("Expected write failure"); }
            catch (IOException expected) { connection.engine.eot(); }
            try { send.get(2, TimeUnit.SECONDS); fail("Ambiguous send reported success"); }
            catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IOException);
                assertFalse(expected.getCause() instanceof LootTransport.NotSentException);
            }
        } finally { sender.shutdownNow(); }
    }

    @Test public void successfulSendThenRemoteIdleCloseUsesFreshAdapterForNextPayload() throws Exception {
        List<MemoryConnection> connections = new CopyOnWriteArrayList<>();
        LootDelivery delivery = new LootDelivery(() -> {
            MemoryConnection connection = new MemoryConnection(); connections.add(connection); return connection;
        }, 2, true, false);
        try {
            assertTrue(delivery.offer(bytes("first"), delivery.generation()));
            LootDeliveryTest.await(() -> connections.size() == 1 && connections.get(0).isOpen());
            MemoryConnection first = connections.get(0);
            assertArrayEquals(bytes("first"), first.writeBinary());
            LootDeliveryTest.await(() -> delivery.snapshot().sentToSocket == 1);
            first.engine.eot(); // Real engine remote-close state; no reflective flag/reset.
            assertFalse(first.isOpen());
            assertTrue(delivery.offer(bytes("next"), delivery.generation()));
            LootDeliveryTest.await(() -> connections.size() == 2 && connections.get(1).isOpen());
            assertArrayEquals(bytes("next"), connections.get(1).writeBinary());
            LootDeliveryTest.await(() -> delivery.snapshot().sentToSocket == 2);
            assertEquals(0, delivery.snapshot().uncertain);
            assertEquals(0, delivery.snapshot().dropped);
            for (MemoryConnection connection : connections) assertTrue("No duplicate payload", connection.engine.outQueue.isEmpty());
        } finally { delivery.close(); assertTrue(delivery.awaitStopped(2000)); }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ByteBuffer frame(int opcode, boolean fin, int length) {
        ByteBuffer buffer = ByteBuffer.allocate((length <= 125 ? 2 : 4) + length);
        buffer.put((byte)(opcode | (fin ? 128 : 0)));
        if (length <= 125) buffer.put((byte)length);
        else buffer.put((byte)126).putShort((short)length);
        buffer.put(new byte[length]); buffer.flip(); return buffer;
    }
    private static MemoryConnection connected() throws Exception {
        MemoryConnection connection = new MemoryConnection(); connection.connect(100); return connection;
    }

    /** All lifecycle/send/state code delegates to the production adapter; only the network is absent. */
    private static final class MemoryConnection implements LootTransport {
        final WebSocket adapter = new WebSocket("ws://127.0.0.1:1");
        final OutputStream output = adapter.observeWrites(new ByteArrayOutputStream());
        volatile WebSocketClient client;
        volatile WebSocketImpl engine;
        HandshakeImpl1Client request;

        void prepareHandshake() throws Exception {
            client = adapter.prepareClient(100);
            engine = (WebSocketImpl)client.getConnection();
            request = new HandshakeImpl1Client(); request.setResourceDescriptor("/"); request.put("Host", "127.0.0.1");
            engine.startHandshake(request);
            engine.outQueue.clear(); // The synthetic server has read the generated HTTP request.
        }

        @Override public void connect(int timeoutMillis) throws Exception {
            prepareHandshake();
            String challenge = request.getFieldValue("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(bytes(challenge)));
            engine.decode(ByteBuffer.wrap(bytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")));
            assertTrue("Production engine must accept the in-memory handshake", isOpen());
        }
        @Override public boolean isOpen() { return adapter.isOpen(); }
        @Override public String lastError() { return adapter.lastError(); }
        @Override public void checkHealth() { adapter.checkHealth(); }
        @Override public void send(byte[] payload, int timeoutMillis) throws Exception { adapter.send(payload, timeoutMillis); }
        @Override public void close() throws IOException { adapter.close(); }

        byte[] writeBinary() throws Exception {
            ByteBuffer frame = engine.outQueue.poll(1, TimeUnit.SECONDS);
            assertNotNull(frame); assertEquals(2, frame.get(0) & 0x0f);
            output.write(frame.array(), 0, frame.limit());
            Draft_6455 server = new Draft_6455(); server.setParseMode(Role.SERVER);
            ByteBuffer decoded = server.translateFrame(frame).get(0).getPayloadData();
            byte[] payload = new byte[decoded.remaining()]; decoded.get(payload); return payload;
        }
    }
}
