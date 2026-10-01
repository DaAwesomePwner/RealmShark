package util;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** One preferences file, an immediate memory view, and a coalescing single writer. */
public final class PreferencesStore {
    public enum State { LOADING, SAVING, SAVED, FAILED }

    public static final class SaveResult {
        public final long generation;
        public final Throwable failure;

        private SaveResult(long generation, Throwable failure) {
            this.generation = generation;
            this.failure = failure;
        }

        public boolean isSuccess() { return failure == null; }
        public static SaveResult saved(long generation) { return new SaveResult(generation, null); }
        public static SaveResult failed(long generation, Throwable failure) {
            return new SaveResult(generation, Objects.requireNonNull(failure));
        }
        public String detail() {
            return isSuccess() ? "Preferences saved (generation " + generation + ")."
                : "Preferences not saved (generation " + generation + "): " + failure.getMessage();
        }
    }

    /** Immutable, latest-generation status; reading it never waits for disk or the writer. */
    public static final class Status {
        public final State state;
        public final long generation;
        public final long savedGeneration;
        public final String detail;

        private Status(State state, long generation, long savedGeneration, String detail) {
            this.state = state;
            this.generation = generation;
            this.savedGeneration = savedGeneration;
            this.detail = detail;
        }
    }

    // Package-local disk seam keeps slow/failing storage tests isolated from application settings.
    interface Storage {
        Properties read(Path path) throws IOException;
        void write(Path path, Properties snapshot) throws IOException;
        default void writeSynced(Path path, Properties snapshot) throws IOException { write(path, snapshot); }
    }

    static class FileStorage implements Storage {
        private boolean sync;
        @Override public Properties read(Path path) throws IOException {
            Properties loaded = new Properties();
            try (FileReader reader = new FileReader(path.toFile())) {
                loaded.load(reader);
            } catch (FileNotFoundException failure) {
                if (!Files.notExists(path)) throw failure;
            }
            return loaded;
        }

        @Override public void write(Path path, Properties snapshot) throws IOException {
            AtomicFiles.write(path, output -> {
                // Keep the historical FileReader/FileWriter (platform charset) format.
                try (OutputStreamWriter writer = new OutputStreamWriter(output, java.nio.charset.Charset.defaultCharset())) {
                    snapshot.store(writer, "RealmShark properties");
                }
            }, sync, this::replace);
        }

        @Override public void writeSynced(Path path, Properties snapshot) throws IOException {
            sync = true;
            try { write(path, snapshot); } finally { sync = false; }
        }

        void replace(Path temporary, Path target) throws IOException {
            AtomicFiles.replace(temporary, target, this::move);
        }
        void move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException { Files.move(source, target, options); }
    }

    private final Object lock = new Object();
    private final Path path;
    private final Properties properties;
    private final Storage storage;
    private final CompletableFuture<SaveResult> loaded = new CompletableFuture<>();
    private CompletableFuture<SaveResult> pending, inFlight;
    private SaveResult lastResult = SaveResult.saved(0);
    private Throwable loadFailure;
    private long generation, savedGeneration;
    private boolean started, closing;
    private volatile Status status = new Status(State.LOADING, 0, 0, "Preferences have not been loaded yet.");

    public PreferencesStore(Path path) { this(path, new Properties(), new FileStorage()); }

    PreferencesStore(Path path, Properties properties, Storage storage) {
        this.path = path.toAbsolutePath();
        this.properties = properties;
        this.storage = storage;
    }

    public String getProperty(String name) {
        synchronized (lock) { return properties.getProperty(name); }
    }

    public Status status() { return status; }

    /**
     * Explicit startup barrier, before constructing GUI/models that cache saved settings.
     * A malformed/unreadable file is left untouched and suspends writes for this store's lifetime.
     */
    public SaveResult preload() {
        requireOffEdt("Preference preload");
        synchronized (lock) { startWorker(); }
        return loaded.join();
    }

    public CompletionStage<SaveResult> setProperties(String name, String value) {
        return setProperties(java.util.Collections.singletonMap(name, value));
    }

    /**
     * Updates memory as one generation. At most one in-flight snapshot and one pending batch
     * exist, regardless of update count. Coalesced callers share completion of the newer snapshot.
     * Completion callbacks run outside our locks and should return promptly (marshal UI to EDT).
     */
    public CompletionStage<SaveResult> setProperties(Map<String, String> updates) {
        Map<String, String> copy = new HashMap<>(updates);
        copy.forEach((key, value) -> { Objects.requireNonNull(key); Objects.requireNonNull(value); });
        synchronized (lock) {
            if (closing) return CompletableFuture.completedFuture(SaveResult.failed(generation,
                new IllegalStateException("Preferences store is shutting down.")));
            if (copy.isEmpty()) return flushLocked();
            properties.putAll(copy);
            generation++;
            if (pending == null) pending = new CompletableFuture<>();
            publishSaving();
            startWorker();
            lock.notifyAll();
            return pending;
        }
    }

    /** Captures completion of all changes accepted so far; it does not block or retry failures. */
    public CompletionStage<SaveResult> flush() {
        synchronized (lock) { return flushLocked(); }
    }

    private CompletableFuture<SaveResult> flushLocked() {
        startWorker();
        if (pending != null) return pending;
        if (inFlight != null) return inFlight;
        if (!loaded.isDone()) return loaded;
        return CompletableFuture.completedFuture(lastResult);
    }

    /** Rejects new updates and waits at most the supplied bound, off the EDT. */
    public SaveResult shutdown(long timeout, TimeUnit unit, Consumer<String> reportFailure) {
        requireOffEdt("Preference shutdown");
        CompletableFuture<SaveResult> completion;
        long target;
        synchronized (lock) {
            // Even an already published routine save needs a final forced publication.
            if (!closing && generation > 0 && pending == null) pending = new CompletableFuture<>();
            closing = true;
            target = generation;
            completion = flushLocked();
            lock.notifyAll();
        }
        SaveResult result;
        try {
            result = completion.get(timeout, unit);
        } catch (TimeoutException failure) {
            result = SaveResult.failed(target, new IOException("Timed out waiting for preferences after "
                + timeout + " " + unit.toString().toLowerCase(java.util.Locale.ROOT) + ".", failure));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            result = SaveResult.failed(target, failure);
        } catch (ExecutionException failure) {
            result = SaveResult.failed(target, failure.getCause());
        }
        if (!result.isSuccess()) reportFailure.accept(result.detail());
        return result;
    }

    private static void requireOffEdt(String operation) {
        if (isEventDispatchThread(Thread.currentThread())) throw new IllegalStateException(operation + " must run off the EDT.");
    }

    /**
     * SwingUtilities.isEventDispatchThread() initializes the toolkit, which never finishes during
     * JVM shutdown on Windows. Every EDT is a java.awt.EventDispatchThread, so check the class.
     */
    static boolean isEventDispatchThread(Thread thread) {
        return thread.getClass().getName().equals("java.awt.EventDispatchThread");
    }

    private void startWorker() {
        if (started) return;
        started = true;
        Thread worker = new Thread(this::runWriter, "preferences-writer");
        worker.setDaemon(true);
        worker.start();
    }

    private void load() {
        Properties fromDisk = null;
        Throwable failure = null;
        try { fromDisk = storage.read(path); }
        catch (IOException | RuntimeException error) { failure = error; }
        SaveResult result;
        synchronized (lock) {
            loadFailure = failure;
            if (failure == null) {
                // Changes accepted during startup (and reflection-based test presets) win.
                for (String key : fromDisk.stringPropertyNames()) {
                    if (!properties.containsKey(key)) properties.setProperty(key, fromDisk.getProperty(key));
                }
            }
            result = failure == null ? SaveResult.saved(0) : SaveResult.failed(0,
                new IOException("Could not load " + path + "; original file preserved. Repair it and restart to resume saving.", failure));
            if (failure != null) loadFailure = result.failure;
            lastResult = result;
            if (pending == null) publishResult(result);
            else publishSaving();
        }
        loaded.complete(result);
    }

    private void runWriter() {
        load();
        for (;;) {
            Properties snapshot;
            long writingGeneration;
            boolean sync;
            CompletableFuture<SaveResult> completion;
            synchronized (lock) {
                while (pending == null) {
                    if (closing) return;
                    try { lock.wait(); }
                    catch (InterruptedException ignored) { /* Only shutdown closes this writer. */ }
                }
                completion = pending;
                pending = null;
                inFlight = completion;
                writingGeneration = generation;
                sync = closing;
                snapshot = new Properties();
                snapshot.putAll(properties);
            }
            SaveResult result;
            try {
                if (loadFailure != null) result = SaveResult.failed(writingGeneration, loadFailure);
                else {
                    if (sync) storage.writeSynced(path, snapshot); else storage.write(path, snapshot);
                    result = SaveResult.saved(writingGeneration);
                }
            } catch (IOException | RuntimeException failure) {
                result = SaveResult.failed(writingGeneration, failure);
            }
            synchronized (lock) {
                inFlight = null;
                lastResult = result;
                if (result.isSuccess()) savedGeneration = writingGeneration;
                if (pending == null) publishResult(result);
                else publishSaving();
            }
            completion.complete(result);
        }
    }

    private void publishSaving() {
        status = new Status(State.SAVING, generation, savedGeneration,
            "Saving preferences (generation " + generation + "; saved through " + savedGeneration + ")."
                + (lastResult.isSuccess() ? "" : " Previous attempt: " + lastResult.detail()));
    }

    private void publishResult(SaveResult result) {
        status = new Status(result.isSuccess() ? State.SAVED : State.FAILED,
            generation, savedGeneration, result.detail());
    }
}
