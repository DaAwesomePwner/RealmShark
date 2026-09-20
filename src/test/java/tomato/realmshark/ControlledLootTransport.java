package tomato.realmshark;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** In-memory, latch-controlled transport. Never constructs a socket. */
public final class ControlledLootTransport implements LootTransport {
    public final CountDownLatch connectEntered = new CountDownLatch(1);
    public final CountDownLatch sendEntered = new CountDownLatch(1);
    public final CountDownLatch closed = new CountDownLatch(1);
    public final CountDownLatch connectGate, sendGate;
    public final List<byte[]> payloads = new CopyOnWriteArrayList<>();
    public final List<Thread> ioThreads = new CopyOnWriteArrayList<>();
    public volatile boolean ignoreConnectInterrupt, ignoreSendInterrupt, failConnect, failSend, failClose, ioOnEdt;
    public volatile boolean open, rejectBeforeEnqueue;

    public ControlledLootTransport(boolean blockConnect, boolean blockSend) {
        connectGate = new CountDownLatch(blockConnect ? 1 : 0);
        sendGate = new CountDownLatch(blockSend ? 1 : 0);
    }

    @Override public void connect(int timeoutMillis) throws Exception {
        recordThread(); connectEntered.countDown();
        awaitGate(connectGate, timeoutMillis, ignoreConnectInterrupt);
        if (failConnect) throw new IOException("Controlled connect failure");
        open = true;
    }

    @Override public boolean isOpen() { return open; }

    @Override public void send(byte[] payload, int timeoutMillis) throws Exception {
        if (!open || rejectBeforeEnqueue) throw new NotSentException("Controlled pre-enqueue rejection");
        recordThread(); payloads.add(Arrays.copyOf(payload, payload.length)); sendEntered.countDown();
        awaitGate(sendGate, timeoutMillis, ignoreSendInterrupt);
        if (failSend) throw new IOException("Controlled ambiguous write failure");
    }

    @Override public void close() throws IOException {
        open = false; recordThread(); closed.countDown();
        if (failClose) throw new IOException("Controlled close failure");
    }
    public void release() { connectGate.countDown(); sendGate.countDown(); }

    private void recordThread() {
        ioThreads.add(Thread.currentThread());
        ioOnEdt |= SwingUtilities.isEventDispatchThread();
    }

    private static void awaitGate(CountDownLatch gate, int timeoutMillis, boolean ignoreInterrupt) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new IOException("Controlled timeout");
            try {
                if (gate.await(left, TimeUnit.NANOSECONDS)) return;
                throw new IOException("Controlled timeout");
            } catch (InterruptedException ex) { if (!ignoreInterrupt) throw ex; }
        }
    }
}
