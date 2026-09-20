package tomato.realmshark;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.SocketFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;

/**
 * Single-use Java-WebSocket 1.5.3 adapter. A failed connection is replaced, never reset/retried.
 * Write accounting relies on the pinned WebSocketClient.WebsocketWriteThread frame-array contract:
 * https://github.com/TooTallNate/Java-WebSocket/blob/v1.5.3/src/main/java/org/java_websocket/client/WebSocketClient.java
 */
public final class WebSocket implements LootTransport {
    static final int MAX_PENDING_PONGS = 8;
    static final int CONTROL_TIMEOUT_MILLIS = 3000;
    private final URI uri;
    private final AtomicLong binaryWrites = new AtomicLong();
    private final Object controlLock = new Object();
    private int pendingPongs;
    private long lastControlProgressNanos;
    private volatile String error;
    private volatile WebSocketClient client;
    private final Socket socket = new Socket() {
        @Override public OutputStream getOutputStream() throws IOException {
            return observeWrites(super.getOutputStream());
        }
    };

    public WebSocket(String uri) { this.uri = URI.create(uri); }

    @Override public void connect(int timeoutMillis) throws Exception {
        // The legacy endpoint is plain ws with a numeric host (no unbounded DNS/TLS operation).
        if (!"ws".equals(uri.getScheme())) throw new IOException("Legacy transport requires ws");
        prepareClient(timeoutMillis);
        if (!client.connectBlocking(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new SocketTimeoutException(error == null ? "Loot connection timed out" : error);
        }
    }

    /** Package-visible for in-memory protocol tests; constructing a client does not connect it. */
    WebSocketClient prepareClient(int timeoutMillis) {
        if (client != null) throw new IllegalStateException("A loot connection is single-use");
        client = new WebSocketClient(uri, new LootWebSocketDraft(this::abort), null, timeoutMillis) {
            @Override public void onOpen(ServerHandshake handshake) { }
            @Override public void onMessage(String message) { /* No application-ACK protocol. */ }
            @Override public void onClose(int code, String reason, boolean remote) {
                error = "Socket closed (" + code + "): " + reason;
            }
            @Override public void onError(Exception ex) { error = ex.toString(); }
            @Override public void onWebsocketPing(org.java_websocket.WebSocket connection, Framedata ping) {
                // Reservation spans BOTH the library queue and the actual socket write. Queue size
                // alone misses a frame already dequeued by a blocked writer. Pings are <=125 bytes.
                if (!isOpen()) return;
                if (!reservePong()) {
                    abort("Loot control-output backpressure: too many pending pongs");
                    return;
                }
                try { super.onWebsocketPing(connection, ping); }
                catch (WebsocketNotConnectedException ex) { releasePong(); }
                catch (RuntimeException ex) { abort("Loot pong failed: " + ex.getClass().getSimpleName()); }
            }
        };
        client.setConnectionLostTimeout(0);
        // Public 1.5.3 API. Owning the socket before connect allows timeout/cancellation to
        // close even a connection whose client thread has not started yet.
        client.setSocketFactory(new SocketFactory() {
            @Override public Socket createSocket() { return socket; }
            @Override public Socket createSocket(String host, int port) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress host, int port) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
        });
        return client;
    }

    @Override public boolean isOpen() { return client != null && client.isOpen() && !socket.isClosed(); }
    @Override public String lastError() { return error == null ? "" : error; }
    @Override public void checkHealth() { checkHealth(System.nanoTime()); }

    void checkHealth(long nowNanos) {
        boolean stalled;
        synchronized (controlLock) {
            stalled = pendingPongs > 0 && nowNanos - lastControlProgressNanos
                >= TimeUnit.MILLISECONDS.toNanos(CONTROL_TIMEOUT_MILLIS);
        }
        if (stalled && isOpen()) abort("Loot control-output write timed out");
    }

    private boolean reservePong() {
        synchronized (controlLock) {
            if (pendingPongs == MAX_PENDING_PONGS) return false;
            if (pendingPongs++ == 0) lastControlProgressNanos = System.nanoTime();
            return true;
        }
    }

    private void releasePong() {
        synchronized (controlLock) { pendingPongs--; lastControlProgressNanos = System.nanoTime(); }
    }

    @Override public void send(byte[] payload, int timeoutMillis) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new NotSentException("Loot send cancelled before enqueue");
        if (!isOpen()) throw new NotSentException(error == null ? "Loot socket is not open" : error);
        long before = binaryWrites.get();
        try { client.send(payload); } // Acceptance by the library alone is NOT a completed socket write.
        catch (WebsocketNotConnectedException ex) {
            // In 1.5.3 this exception is thrown before WebSocketImpl.write enqueues anything.
            throw new NotSentException("Loot socket closed before enqueue");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (binaryWrites.get() == before) {
            if (!isOpen()) throw new IOException(error == null ? "Loot socket closed during write" : error);
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("Loot socket write timed out; delivery uncertain");
            Thread.sleep(5);
        }
        // OutputStream.write returned: accepted by the local socket, not confirmed by the server.
    }

    OutputStream observeWrites(OutputStream output) {
        return new FilterOutputStream(output) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
                // 1.5.3 writes each encoded frame as one array; only one binary frame is in flight.
                if (length > 0 && (bytes[offset] & 0x0f) == 2) binaryWrites.incrementAndGet();
                if (length > 0 && (bytes[offset] & 0x0f) == 10) releasePong();
            }
        };
    }

    private void abort(String reason) {
        error = reason;
        try { socket.close(); }
        catch (IOException ex) { error = reason + ": " + ex.getClass().getSimpleName(); }
        finally { if (client != null) client.closeConnection(1006, reason); }
    }

    @Override public void close() throws IOException {
        // Close the raw socket FIRST: 1.5.3's writer attempts to drain on interruption.
        // This aborts blocked writes and late connects instead of allowing a post-opt-out drain.
        try { socket.close(); }
        finally { if (client != null) client.closeConnection(1006, "Loot sender released connection"); }
    }
}
