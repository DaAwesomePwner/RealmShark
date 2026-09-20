package tomato.realmshark;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/** Bounded FIFO, one lazy daemon sender, no ambiguous retries or I/O under the state lock. */
public final class LootDelivery implements AutoCloseable {
    public static final int DEFAULT_CAPACITY = 256;
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    public static final int CONNECT_TIMEOUT_MILLIS = 3000;
    public static final int SEND_TIMEOUT_MILLIS = 3000;
    private final Object lock = new Object();
    private final LootTransport.Factory factory;
    private final int capacity;
    private final boolean preview;
    private final Deque<Entry> queue = new ArrayDeque<>();
    private Thread worker;
    private boolean enabled, closed;
    private long generation, queued, sentToSocket, dropped, uncertain;
    private String state = "Idle", lastError = "";
    private Entry active;

    public LootDelivery(LootTransport.Factory factory, int capacity, boolean enabled, boolean preview) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        this.factory = factory;
        this.capacity = capacity;
        this.enabled = enabled;
        this.preview = preview;
    }

    public long generation() { synchronized (lock) { return generation; } }
    public boolean isEnabled() { synchronized (lock) { return enabled && !preview && !closed; } }

    /** Copies before publication. Overflow drops the newest payload, preserving accepted FIFO order. */
    public boolean offer(byte[] payload, long expectedGeneration) {
        if (payload.length > MAX_PAYLOAD_BYTES) {
            recordDrop("Loot payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
            return false;
        }
        byte[] owned = Arrays.copyOf(payload, payload.length);
        synchronized (lock) {
            if (!enabled || preview || closed || generation != expectedGeneration) {
                dropped++;
                return false;
            }
            if (queue.size() >= capacity) {
                dropped++;
                lastError = "Loot queue full; newest payload dropped";
                return false;
            }
            queue.addLast(new Entry(owned, generation));
            queued++;
            if (worker == null) {
                worker = new Thread(this::run, "legacy-loot-sender");
                worker.setDaemon(true);
                worker.start();
            }
            lock.notifyAll();
            return true;
        }
    }

    public void setEnabled(boolean value) {
        synchronized (lock) {
            if (enabled == value || closed) return;
            enabled = value;
            generation++;
            discardUnsent();
            if (worker != null) worker.interrupt();
            lock.notifyAll();
        }
    }

    public void recordDrop(String reason) {
        synchronized (lock) { dropped++; if (reason != null) lastError = reason; }
    }

    public Status snapshot() {
        synchronized (lock) {
            return new Status(enabled && !preview && !closed, preview, closed, queue.size(),
                active == null ? 0 : 1, queued, sentToSocket, dropped, uncertain,
                closed ? "Stopped" : preview ? "Preview — sending disabled" : !enabled ? "Opted out" : state, lastError);
        }
    }

    private void discardUnsent() {
        dropped += queue.size();
        queue.clear();
        if (active != null && !active.finished) finish(active, active.sending ? Outcome.UNCERTAIN : Outcome.DROPPED);
    }

    private boolean valid(Entry entry) {
        return enabled && !preview && !closed && generation == entry.generation && !entry.finished;
    }

    private void run() {
        LootTransport connection = null;
        long connectionGeneration = -1;
        try {
            while (true) {
                Entry entry;
                synchronized (lock) {
                    if (closed) break;
                    entry = queue.pollFirst();
                    if (entry != null) { active = entry; state = "Connecting"; }
                }
                if (entry == null) {
                    // Even idle opt-out closes on this worker, never on capture/EDT.
                    try {
                        if (connection != null) connection.checkHealth();
                        if (connection != null && (connectionGeneration != generation() || !connection.isOpen())) {
                            rememberConnectionError(connection);
                            release(connection); connection = null;
                        }
                    } catch (Exception ex) {
                        synchronized (lock) { lastError = describe(ex); }
                        if (connection != null) { release(connection); connection = null; }
                    }
                    synchronized (lock) {
                        if (closed) break;
                        state = "Idle";
                        if (queue.isEmpty()) {
                            // Observe idle closes/adapter pressure failures even with no new loot.
                            try { lock.wait(250); } catch (InterruptedException ignored) { }
                        }
                    }
                    continue;
                }
                boolean failed = false;
                try {
                    Thread.interrupted(); // A previous generation's cancellation must not poison new work.
                    if (connection != null) connection.checkHealth();
                    if (connection != null && (connectionGeneration != entry.generation || !connection.isOpen())) {
                        rememberConnectionError(connection);
                        release(connection); connection = null;
                    }
                    for (int attempt = 0; attempt < 2; attempt++) {
                        synchronized (lock) { if (!valid(entry)) break; }
                        if (connection == null) {
                            connection = factory.create();
                            connectionGeneration = entry.generation;
                            connection.connect(CONNECT_TIMEOUT_MILLIS);
                        }
                        synchronized (lock) {
                            if (!valid(entry)) break;
                            // Linearization point: after this, disable cannot promise retraction.
                            entry.sending = true;
                            state = "Writing to socket (unconfirmed)";
                        }
                        try {
                            connection.send(entry.payload, SEND_TIMEOUT_MILLIS);
                            synchronized (lock) { finish(entry, valid(entry) ? Outcome.SENT : Outcome.UNCERTAIN); }
                            break;
                        } catch (LootTransport.NotSentException ex) {
                            synchronized (lock) {
                                entry.sending = false;
                                lastError = describe(ex);
                                // A concurrent opt-out may already have classified this operation.
                                // Positive pre-enqueue evidence resolves that uncertainty as unsent.
                                if (entry.outcome == Outcome.UNCERTAIN) {
                                    uncertain--; dropped++; entry.outcome = Outcome.DROPPED;
                                }
                                state = "Reconnecting before enqueue";
                            }
                            release(connection); connection = null;
                            if (attempt == 1) throw ex;
                            // Only this explicit pre-enqueue rejection gets one fresh connection.
                            // The next loop rechecks opt-out/generation before any reconnect/send.
                        }
                    }
                } catch (Exception ex) {
                    failed = true;
                    synchronized (lock) {
                        if (!entry.finished) {
                            lastError = describe(ex);
                            finish(entry, entry.sending ? Outcome.UNCERTAIN : Outcome.DROPPED);
                        }
                    }
                } finally {
                    boolean stale;
                    synchronized (lock) {
                        stale = generation != entry.generation || closed;
                        if (!entry.finished) finish(entry, entry.sending ? Outcome.UNCERTAIN : Outcome.DROPPED);
                        active = null;
                        state = "Idle";
                    }
                    if ((failed || stale) && connection != null) { release(connection); connection = null; }
                }
            }
        } finally {
            if (connection != null) release(connection);
        }
    }

    private void release(LootTransport connection) {
        try { connection.close(); }
        catch (Exception ex) { synchronized (lock) { lastError = "Close: " + describe(ex); } }
    }

    private void rememberConnectionError(LootTransport connection) {
        String error = connection.lastError();
        if (error != null && !error.isEmpty()) {
            if (error.length() > 240) error = error.substring(0, 240);
            synchronized (lock) { lastError = error.replace('\n', ' ').replace('\r', ' '); }
        }
    }

    private static String describe(Exception ex) {
        String text = ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage());
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() > 240 ? text.substring(0, 240) : text;
    }

    private void finish(Entry entry, Outcome outcome) {
        if (entry.finished) return;
        entry.finished = true;
        entry.outcome = outcome;
        if (outcome == Outcome.SENT) sentToSocket++;
        else if (outcome == Outcome.UNCERTAIN) uncertain++;
        else dropped++;
    }

    /** Nonblocking cancellation, safe on capture/EDT. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            discardUnsent();
            if (worker != null) worker.interrupt();
            lock.notifyAll();
        }
    }

    /** Optional bounded wait for orderly shutdown; call outside the EDT. */
    public boolean awaitStopped(long timeoutMillis) throws InterruptedException {
        Thread thread;
        synchronized (lock) { thread = worker; }
        if (thread == null) return true;
        if (timeoutMillis > 0) TimeUnit.MILLISECONDS.timedJoin(thread, timeoutMillis);
        return !thread.isAlive();
    }

    private enum Outcome { SENT, DROPPED, UNCERTAIN }
    private static final class Entry {
        final byte[] payload;
        final long generation;
        boolean sending, finished;
        Outcome outcome;
        Entry(byte[] payload, long generation) { this.payload = payload; this.generation = generation; }
    }

    public static final class Status {
        public final boolean enabled, preview, closed;
        public final int waiting, active;
        public final long queued, sentToSocket, dropped, uncertain;
        public final String state, lastError;
        private Status(boolean enabled, boolean preview, boolean closed, int waiting, int active,
                long queued, long sentToSocket, long dropped, long uncertain, String state, String lastError) {
            this.enabled = enabled; this.preview = preview; this.closed = closed;
            this.waiting = waiting; this.active = active; this.queued = queued;
            this.sentToSocket = sentToSocket; this.dropped = dropped; this.uncertain = uncertain;
            this.state = state; this.lastError = lastError;
        }
    }
}
