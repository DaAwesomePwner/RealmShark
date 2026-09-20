package tomato.gui.stats;

import java.awt.event.HierarchyEvent;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** One pending presentation callback per fame view; no captured samples in the queue. */
final class FameRefresh {
    private final Runnable render;
    private boolean showing, dirty, pending;
    private long scheduled, rendered;

    FameRefresh(JComponent view, Runnable render) {
        this.render = render;
        showing = view.isShowing();
        view.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                synchronized (this) { showing = view.isShowing(); }
                request();
            }
        });
    }

    synchronized void request() {
        dirty = true;
        schedule();
    }

    private void schedule() {
        if (showing && dirty && !pending) {
            pending = true; scheduled++;
            SwingUtilities.invokeLater(this::drain);
        }
    }

    private void drain() {
        try {
            boolean refresh;
            synchronized (this) { refresh = showing && dirty; if (refresh) dirty = false; }
            if (refresh) { render.run(); synchronized (this) { rendered++; } }
        } finally {
            synchronized (this) { pending = false; schedule(); }
        }
    }

    /** Explicit EDT refresh for detached fixtures; queued callbacks read only current state. */
    void refreshNow(Runnable explicitRender) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Fame presentation requires the EDT");
        synchronized (this) { dirty = false; }
        explicitRender.run();
        synchronized (this) { rendered++; }
    }

    synchronized Counts counts() { return new Counts(pending ? 1 : 0, scheduled, rendered); }

    static final class Counts {
        final int pending;
        final long scheduled, rendered;
        Counts(int pending, long scheduled, long rendered) { this.pending = pending; this.scheduled = scheduled; this.rendered = rendered; }
    }
}
