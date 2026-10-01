package tomato.gui.dps;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.*;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import tomato.backend.data.DpsData;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.encounter.CombatRetention;
import tomato.history.encounter.CombatSettings;

/**
 * Saves every closed combat recording (spec §8.4) on one daemon worker, {@value #THREAD}: never the capture producer (which
 * only hands a closed {@link DpsData} off, after {@code TomatoData.clear()} returned), the EDT or the history I/O worker.
 * Per recording it builds the {@link CombatSummaries} pair and puts the detail and the card record ({@link CombatFacts}
 * modules, keyed by recording ID). With Keep full combat detail on it first writes the recording without its debug packet
 * log ({@code getSaveFile(false)}: chat and account packets never leave memory) atomically to
 * {@code <current session>/combat-full/<checkpointName(recordingId)>.dps}, and marks the record. It also runs
 * {@link CombatRetention} at start and after each Combat history change. A read-only (preview) history saves and prunes
 * nothing. A failure is logged and counted, and the worker goes on with the next recording. A fight still open when the app
 * exits is never closed, so it is not saved.
 *
 * <p>Closing (the app's shutdown, before the history store closes) waits for the queued saves. A save commits its detail and
 * record together under one lock, so once closing gives up waiting ({@value #DRAIN_MILLIS} ms) it cancels the rest with no
 * partial state: a fight not yet committed writes neither, and deletes the full-detail file it already wrote. The same holds
 * for a fight that finds the store already closing.
 */
public final class CombatAutosave implements AutoCloseable {
    public static final String THREAD = "RealmShark combat history";
    /** How long closing waits for queued saves (the app's shutdown, before the history store closes). */
    static final long DRAIN_MILLIS = 15_000;
    /** How long closing then waits for a cancelled save to stop. */
    private static final long STOP_MILLIS = 2_000;
    /** Java serialization recurses through the hit graph: a long Realm recording needs a deep stack. */
    private static final long STACK_BYTES = 64L << 20;
    private static volatile CombatAutosave installed;

    private final SessionStore store;
    private final Supplier<CombatSettings.Values> settings;
    private final LongSupplier clock;
    private final ThreadPoolExecutor worker;
    /** Stops a running prune at once when closing: pruning can wait for the next start. */
    private final Cancellation pruneCancel = new Cancellation();
    /** Guards each save's commit (its detail and record puts) against closing's cancellation. */
    private final Object commit = new Object();
    private boolean savesCancelled;   // guarded by commit
    private final long drainMillis;
    /** Test hook: runs after a save wrote its full detail (if any), just before it commits. */
    Runnable beforeCommit = () -> { };
    private final AtomicBoolean pruneQueued = new AtomicBoolean();
    private final AtomicInteger failures = new AtomicInteger();
    private final Runnable pruneOnChange = this::prune;
    private volatile CombatRetention.Result lastPrune;
    private volatile boolean closed;

    /** @param settings read on the worker for each save and prune; {@code clock} dates pruning (epoch ms) */
    public CombatAutosave(SessionStore store, Supplier<CombatSettings.Values> settings, LongSupplier clock) {
        this(store, settings, clock, DRAIN_MILLIS);
    }

    /** Tests: {@code drainMillis} is how long {@link #close} waits for queued saves before it cancels the rest. */
    CombatAutosave(SessionStore store, Supplier<CombatSettings.Values> settings, LongSupplier clock, long drainMillis) {
        this.drainMillis = drainMillis;
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(null, task, THREAD, STACK_BYTES); thread.setDaemon(true); return thread;
        });
        worker.allowCoreThreadTimeOut(true);   // no idle thread between fights
    }

    /**
     * The app's autosave over its history store: receives capture's closed recordings ({@link #closed}), prunes now (the store
     * is open) and again after every Combat history change, until {@link #close}.
     */
    public static CombatAutosave start(SessionStore store) {
        CombatAutosave autosave = new CombatAutosave(store, CombatSettings::current, System::currentTimeMillis);
        CombatSettings.onChange(autosave.pruneOnChange);
        installed = autosave;
        autosave.prune();
        return autosave;
    }

    /** Capture's hand-off (producer thread): queues a closed recording for the started autosave, if any. Never blocks. */
    public static void closed(DpsData data) {
        CombatAutosave autosave = installed;
        if (autosave != null) autosave.submit(data);
    }

    /** Where a recording's full detail is kept inside its session folder. */
    public static Path fullDetailFile(Path session, String recordingId) {
        return session.resolve(CombatRetention.FULL_DETAIL).resolve(SessionStore.checkpointName(recordingId) + ".dps");
    }

    /** Queues one closed recording; any thread, never blocks. Its graph must no longer change (capture closed it). */
    public void submit(DpsData data) {
        if (data == null || closed || !store.writable()) return;
        try { worker.execute(() -> save(data)); }
        catch (RejectedExecutionException shutDown) { /* closing: the app is exiting */ }
    }

    /** Queues a prune unless one is already waiting (it reads the newest settings when it runs). */
    public void prune() {
        if (closed || !store.writable() || !pruneQueued.compareAndSet(false, true)) return;
        try { worker.execute(() -> { pruneQueued.set(false); runPrune(); }); }
        catch (RejectedExecutionException shutDown) { pruneQueued.set(false); }
    }

    /** What the last prune deleted, or null before one finished. */
    public CombatRetention.Result lastPrune() { return lastPrune; }

    /** Saves and prunes that failed (logged) since this autosave was made. */
    public int failures() { return failures.get(); }

    /** Waits until everything queued before this call has run; false when {@code timeoutMillis} passed first. */
    public boolean flush(long timeoutMillis) throws InterruptedException {
        FutureTask<Void> marker = new FutureTask<>(() -> { }, null);
        try { worker.execute(marker); }
        catch (RejectedExecutionException shutDown) { return worker.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS); }
        try { marker.get(timeoutMillis, TimeUnit.MILLISECONDS); return true; }
        catch (TimeoutException late) { return false; }
        catch (ExecutionException impossible) { return true; }
    }

    /**
     * Stops taking work and stops a running prune, then waits for the queued saves before the store closes. After
     * {@value #DRAIN_MILLIS} ms it cancels the rest: queued fights are dropped and a fight being saved commits nothing (see the
     * class notes), so the store never receives half a fight.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        CombatSettings.removeOnChange(pruneOnChange);
        if (installed == this) installed = null;
        pruneCancel.cancel();
        worker.shutdown();
        try {
            if (worker.awaitTermination(drainMillis, TimeUnit.MILLISECONDS)) return;
            cancelSaves();
            int dropped = worker.shutdownNow().size();
            worker.awaitTermination(STOP_MILLIS, TimeUnit.MILLISECONDS);
            System.err.println(THREAD + ": closing before every fight was saved; " + dropped + " queued fight(s) and any fight being"
                + " saved were cancelled without partial files");
        } catch (InterruptedException e) {
            cancelSaves();
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** From now on a save that has not committed writes nothing (it deletes a full-detail file it wrote). */
    void cancelSaves() { synchronized (commit) { savesCancelled = true; } }

    private boolean savesCancelled() { synchronized (commit) { return savesCancelled; } }

    private void save(DpsData data) {
        String id = data.getRecordingId();
        if (id == null || id.isEmpty() || savesCancelled()) return;   // unkeyed (capture's recordings always have one), or closing
        Path full = null;
        try {
            boolean keepFull = settings.get().keepFullDetail();
            CombatSummaries.Result result = CombatSummaries.build(data);
            if (savesCancelled()) return;
            CombatRecord record = result.record();
            full = keepFull ? writeFullDetail(data, id) : null;   // written before the record that announces it
            record.fullDetail = full != null;
            beforeCommit.run();
            synchronized (commit) {
                // Cancelled by closing, or the store is already closing (its puts would be dropped): nothing is committed.
                if (savesCancelled || store.currentDirectory().isEmpty()) { discard(full); return; }
                store.put(CombatFacts.DETAILS, id, result.detail());   // the detail first: a readable record finds its detail
                store.put(CombatFacts.RECORDS, id, record);
            }
        } catch (RuntimeException | StackOverflowError e) {
            discard(full);
            failure("A closed fight could not be saved", e);
        }
    }

    /** The full-detail file written, or null when the store is closing or the write failed (logged). */
    private Path writeFullDetail(DpsData data, String id) {
        Optional<Path> session = store.currentDirectory();
        if (session.isEmpty()) return null;   // the store is closing
        Path target = fullDetailFile(session.get(), id);
        try { write(target, data.getSaveFile(false), closed); return target; }
        catch (IOException | RuntimeException | StackOverflowError e) { failure("Full combat detail could not be saved", e); return null; }
    }

    /** Deletes a full-detail file whose fight was not committed, so no file outlives its missing record. */
    private void discard(Path full) {
        if (full == null) return;
        try { Files.deleteIfExists(full); }
        catch (IOException e) { failure("An uncommitted full combat detail file could not be deleted", e); }
    }

    /** Java serialization of one recording (the {@code .dps} format), staged beside the target and moved into place. */
    private static void write(Path target, DpsData saved, boolean sync) throws IOException {
        Files.createDirectories(target.getParent());
        util.AtomicFiles.write(target, stream -> {
            try (ObjectOutputStream output = new ObjectOutputStream(new BufferedOutputStream(stream))) {
                output.writeObject(saved);
            }
        }, sync);
    }

    private void runPrune() {
        try { lastPrune = CombatRetention.prune(store, settings.get(), clock.getAsLong(), pruneCancel); }
        catch (CancellationException stopped) { /* closing */ }
        catch (IOException | RuntimeException e) { failure("Combat history could not be pruned", e); }
    }

    private void failure(String message, Throwable cause) {
        failures.incrementAndGet();
        System.err.println(THREAD + ": " + message + " (" + cause.getClass().getSimpleName() + ")");
    }
}
