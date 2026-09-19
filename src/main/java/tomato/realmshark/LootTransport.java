package tomato.realmshark;

import java.io.IOException;

/**
 * One connection, used and closed exclusively by the loot sender. Connect/send must honor their
 * finite deadlines and interruption; close must abort promptly without an unbounded join.
 * A successful send means the local socket accepted the bytes, never application acknowledgement.
 */
public interface LootTransport extends AutoCloseable {
    void connect(int timeoutMillis) throws Exception;
    /** Actual connection state; must be nonblocking. */
    boolean isOpen();
    default String lastError() { return ""; }
    /** Sender-only, nonblocking maintenance (including stalled idle control-output deadlines). */
    default void checkHealth() { }
    void send(byte[] payload, int timeoutMillis) throws Exception;
    @Override void close() throws Exception;

    interface Factory {
        LootTransport create() throws Exception;
    }

    /** Positive evidence that send rejected the payload BEFORE enqueue/write; safe to reconnect once. */
    final class NotSentException extends IOException {
        public NotSentException(String message) { super(message); }
    }
}
