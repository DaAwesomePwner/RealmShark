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
 */
public final class CombatAutosave implements AutoCloseable {
    public static final String THREAD = "RealmShark combat history";
    /** How long closing waits for queued saves (the app's shutdown, before the history store closes). */
    private static final long CLOSE_MILLIS = 5_000;
    /** Java serialization recurses through the hit graph: a long Realm recording needs a deep stack. */
    private static final long STACK_BYTES = 64L << 20;
    private static volatile CombatAutosave installed;

    private final SessionStore store;
    private final Supplier<CombatSettings.Values> settings;
    private final LongSupplier clock;
    private final ThreadPoolExecutor worker;
    private final Cancellation cancel = new Cancellation();
    private final AtomicBoolean pruneQueued = new AtomicBoolean();
    private final AtomicInteger failures = new AtomicInteger();
    private final Runnable pruneOnChange = this::prune;
    private volatile CombatRetention.Result lastPrune;
    private volatile boolean closed;

    /** @param settings read on the worker for each save and prune; {@code clock} dates pruning (epoch ms) */
    public CombatAutosave(SessionStore store, Supplier<CombatSettings.Values> settings, LongSupplier clock) {
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

    /** Stops taking work, stops a running prune, and waits a few seconds for queued saves. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        CombatSettings.removeOnChange(pruneOnChange);
        if (installed == this) installed = null;
        cancel.cancel();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(CLOSE_MILLIS, TimeUnit.MILLISECONDS))
                System.err.println(THREAD + ": closing before every queued fight was saved");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void save(DpsData data) {
        String id = data.getRecordingId();
        if (id == null || id.isEmpty()) return;   // cannot be keyed; capture's recordings always have one
        try {
            boolean keepFull = settings.get().keepFullDetail();
            CombatSummaries.Result result = CombatSummaries.build(data);
            CombatRecord record = result.record();
            record.fullDetail = keepFull && writeFullDetail(data, id);   // written before the record that announces it
            store.put(CombatFacts.DETAILS, id, result.detail());   // the detail first: a readable record finds its detail
            store.put(CombatFacts.RECORDS, id, record);
        } catch (RuntimeException | StackOverflowError e) { failure("A closed fight could not be saved", e); }
    }

    private boolean writeFullDetail(DpsData data, String id) {
        Optional<Path> session = store.currentDirectory();
        if (session.isEmpty()) return false;   // the store is closing
        try { write(fullDetailFile(session.get(), id), data.getSaveFile(false)); return true; }
        catch (IOException | RuntimeException | StackOverflowError e) { failure("Full combat detail could not be saved", e); return false; }
    }

    /** Java serialization of one recording (the {@code .dps} format), staged beside the target and moved into place. */
    private static void write(Path target, DpsData saved) throws IOException {
        Path folder = Files.createDirectories(target.getParent());
        Path staged = Files.createTempFile(folder, ".combat-", ".tmp");
        try {
            try (ObjectOutputStream output = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(staged)))) {
                output.writeObject(saved);
            }
            try { Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(staged); }
    }

    private void runPrune() {
        try { lastPrune = CombatRetention.prune(store, settings.get(), clock.getAsLong(), cancel); }
        catch (CancellationException stopped) { /* closing */ }
        catch (IOException | RuntimeException e) { failure("Combat history could not be pruned", e); }
    }

    private void failure(String message, Throwable cause) {
        failures.incrementAndGet();
        System.err.println(THREAD + ": " + message + " (" + cause.getClass().getSimpleName() + ")");
    }
}
