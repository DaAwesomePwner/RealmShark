package tomato.gui.glance.character;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tomato.history.SessionStore;

/**
 * Feeds Sheet › Fame. Saved history is read on its own daemon thread, "character-fame", never inside the sheet's rebuilds (they
 * run whenever live data moves): once when the sheet opens a key ({@link #open}), then at most every 30 s while the Fame tab is
 * showing ({@link #refresh}). FameHistory keeps finished sessions between reads. Every read carries its key and a generation; the
 * EDT applies only the newest read for the key still shown. The Fame tile comes from the sheet's own builds ({@link #current},
 * built on "character-sheet" from the journal copy and the character in game), so it follows live fame within the sheet's 1 s
 * refresh without reading history; a read waits for the first such tile of its key, so a tile never reads unknown merely because
 * the sheet's build is still running. A failed read is logged once with its stack trace (SheetPresenter.errorLog), shown as a warn
 * banner over what is shown, and retried on the 30 s cadence. EDT only, except the read itself.
 */
final class FamePresenter {
    /** Home's saved-history cadence (HomeRefresher.ARCHIVE_MILLIS). */
    static final long READ_MILLIS = 30_000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "character-fame"); thread.setDaemon(true); return thread;
    });
    private final FameTab tab;
    /** Confined to whichever thread runs the reads: one at a time, in order (the shared "character-fame" thread in production). */
    private final FameHistory history;
    private final LongSupplier clock;
    private final Executor worker;
    private String key;
    private long generation;
    /** When the last read was requested (clock ms); the next one is due READ_MILLIS later. */
    private long readAt;
    /** The sheet's newest Fame tile for {@link #key}; null until its first build applies. */
    private FameModel.Current current;
    /** The model shown; {@code pending}: the newest read, held until {@link #current} arrives. */
    private FameModel model, pending;
    /** The failure last logged, so one that repeats on every retry is logged once until a read applies again. */
    private String logged;

    /** Production: reads {@code store} (AppHistory::store) on the shared "character-fame" thread. */
    FamePresenter(Supplier<SessionStore> store, LongSupplier clock) { this(new FameTab(), new FameHistory(store), clock, WORKER); }

    /** Tests: {@code worker} runs reads one at a time; a test's executor may hold them and run them in any order. */
    FamePresenter(FameTab tab, FameHistory history, LongSupplier clock, Executor worker) {
        this.tab = Objects.requireNonNull(tab, "tab");
        this.history = Objects.requireNonNull(history, "history");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    FameTab tab() { return tab; }

    /** EDT: the model shown, or null (loading). */
    FameModel model() { return model; }

    /** EDT: the sheet shows {@code key}; read its history now. A new key clears the tab until its own read and tile arrive. */
    void open(String key) {
        if (!Objects.equals(key, this.key)) {
            this.key = key;
            current = null; model = null; pending = null;
            tab.apply(null);
            tab.failed(null);
        }
        request();
    }

    /**
     * EDT, once a second while the sheet shows: re-read when the Fame tab is showing and the last read is 30 s old; the Fame
     * tile's "as of …" age advances either way.
     */
    void refresh(boolean showing) {
        if (key == null) return;
        if (showing && clock.getAsLong() - readAt >= READ_MILLIS) request();
        tab.apply(model);
    }

    /** EDT: the sheet's newest build for {@code forKey} made this Fame tile; a tile for any other key is ignored. */
    void current(String forKey, FameModel.Current tile) {
        if (!Objects.equals(forKey, key) || tile == null) return;
        current = tile;
        if (pending != null) { FameModel read = pending; pending = null; show(read.withCurrent(tile)); }
        else if (model != null && !tile.equals(model.current())) show(model.withCurrent(tile));
    }

    private void request() {
        readAt = clock.getAsLong();
        long requested = ++generation;
        String target = key;
        worker.execute(() -> {
            FameModel built = null;
            Throwable failure = null;
            // Any Throwable: an Error escaping here would kill the thread silently and leave the tab without reads.
            try {
                FameHistory.Ref ref = FameHistory.parse(target);
                FameHistory.Series series = ref == null ? FameHistory.Series.EMPTY : history.read(ref.account(), ref.characterId());
                built = FameModel.build(target, series, (FameModel.Current) null);
            } catch (Throwable e) {
                failure = e;
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            FameModel result = built;
            Throwable failed = failure;
            SwingUtilities.invokeLater(() -> deliver(requested, target, result, failed));
        });
    }

    /** EDT: one read's outcome. Every failure is logged; only the newest read of the key still shown applies or warns. */
    private void deliver(long requested, String target, FameModel result, Throwable failure) {
        if (failure != null) log(failure);
        if (requested != generation || !Objects.equals(target, key)) return;
        if (failure != null) { tab.failed(reason(failure)); return; }
        logged = null;
        tab.failed(null);
        if (current == null) pending = result; // the sheet's first build of this key has not applied yet
        else show(result.withCurrent(current));
    }

    private void show(FameModel value) {
        model = value;
        tab.apply(value);
    }

    /** One line: the failure's message without its final period, else its class. */
    private static String reason(Throwable failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().replaceAll("[.\\s]+$", "");
        return message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private void log(Throwable failure) {
        StringWriter trace = new StringWriter();
        try (PrintWriter out = new PrintWriter(trace)) { failure.printStackTrace(out); }
        String text = trace.toString();
        if (text.equals(logged)) return;
        logged = text;
        SheetPresenter.errorLog.accept("[Character sheet] Fame history could not be read; it is retried every 30 s while the Fame tab shows: " + text);
    }
}
