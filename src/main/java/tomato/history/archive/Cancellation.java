package tomato.history.archive;

import java.util.concurrent.CancellationException;

/** Cooperative cancellation shared by a reader, sorter and exporter. */
public final class Cancellation {
    private volatile boolean cancelled;
    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    public void check() { if (isCancelled()) throw new CancellationException("Archive operation cancelled"); }
}
