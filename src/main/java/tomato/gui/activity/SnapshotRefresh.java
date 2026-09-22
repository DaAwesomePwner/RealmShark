package tomato.gui.activity;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** EDT-owned, one running read plus one replaceable pending read. Never waits on a model lock. */
public final class SnapshotRefresh<T> {
    private Request pending;
    private boolean running;
    private Object key;
    private volatile long generation;

    public void request(Object selection, Supplier<T> read, Consumer<T> apply, Consumer<Exception> failure) {
        request(selection,read,apply,failure,value->{});
    }
    /** Disposes an unused result when its generation is superseded while the reader is running. */
    public void request(Object selection, Supplier<T> read, Consumer<T> apply, Consumer<Exception> failure, Consumer<T> discard) {
        requireEdt();
        if (!Objects.equals(key,selection)) { key=selection; generation++; }
        pending=new Request(generation,read,apply,failure,discard);
        if (!running) start();
    }
    /** Drops pending work and makes the in-flight completion inert, without spawning a replacement. */
    public void invalidate() { requireEdt(); generation++; key=null; pending=null; }
    private void start() {
        Request request=pending; pending=null; running=true;
        new SwingWorker<T,Void>() {
            protected T doInBackground() { return request.generation==generation ? request.read.get() : null; }
            protected void done() {
                try {
                    T value=get();
                    if (value!=null) {
                        if (request.generation==generation) request.apply.accept(value);
                        else request.discard.accept(value);
                    }
                } catch (Exception error) {
                    if (request.generation==generation) request.failure.accept(error);
                } finally {
                    running=false;
                    if (pending!=null) start();
                }
            }
        }.execute();
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Snapshot refresh must be requested on the EDT");
    }
    private final class Request {
        final long generation;
        final Supplier<T> read;
        final Consumer<T> apply;
        final Consumer<Exception> failure;
        final Consumer<T> discard;
        Request(long generation,Supplier<T> read,Consumer<T> apply,Consumer<Exception> failure,Consumer<T> discard) {
            this.generation=generation; this.read=read; this.apply=apply; this.failure=failure; this.discard=discard;
        }
    }
}
