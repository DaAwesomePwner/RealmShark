package tomato.history.archive;

import java.util.concurrent.CancellationException;

/** Cooperative cancellation shared by a reader, sorter and exporter. */
public final class Cancellation {
    private volatile boolean cancelled;
    private final java.util.function.BooleanSupplier external;
    public Cancellation(){this(()->false);}
    /** Connects cooperative I/O to a Future whose cancel(false) does not interrupt its writer. */
    public Cancellation(java.util.function.BooleanSupplier external){this.external=java.util.Objects.requireNonNull(external);}
    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled || external.getAsBoolean() || Thread.currentThread().isInterrupted(); }
    public void check() { if (isCancelled()) throw new CancellationException("Archive operation cancelled"); }
}
