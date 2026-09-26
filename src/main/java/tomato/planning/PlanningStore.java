package tomato.planning;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local manual plans. Disk publication is serialized; failed writes never replace durable state. */
public final class PlanningStore implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final class SavedAccount { long revision; PlanData.AccountPlan plan = new PlanData.AccountPlan(); }
    private static final class Document { int version = 1; Map<String, SavedAccount> accounts = new LinkedHashMap<>(); }
    public static final class Snapshot {
        public final long revision;
        public final boolean ready, readOnly;
        public final String status;
        private final PlanData.AccountPlan plan;
        private Snapshot(long revision, boolean ready, boolean readOnly, String status, PlanData.AccountPlan plan) {
            this.revision = revision; this.ready = ready; this.readOnly = readOnly; this.status = status; this.plan = PlanData.copy(plan);
        }
        public PlanData.AccountPlan plan() { return PlanData.copy(plan); }
    }
    public static final class SaveResult {
        public final boolean saved;
        public final String message;
        public final long revision;
        private SaveResult(boolean saved, String message, long revision) { this.saved = saved; this.message = message; this.revision = revision; }
    }
    public interface FileWriter { void write(Path path, String json) throws IOException; }
    private final Path path;
    private final FileWriter fileWriter;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "manual-plans"); t.setDaemon(true); return t; });
    private Document document = new Document();
    private boolean ready, readOnly, closed;
    private String status = "Loading local plans…";
    public PlanningStore(Path path) { this(path, PlanningStore::writeFile); }
    public PlanningStore(Path path, FileWriter fileWriter) {
        this.path = path; this.fileWriter = fileWriter;
        writer.execute(this::load);
    }
    public static PlanningStore memory() { return new PlanningStore(null); }
    private static final class Shared {
        static final PlanningStore INSTANCE = create();
        private static PlanningStore create() {
            PlanningStore value = tomato.Tomato.isPreview() ? memory() : new PlanningStore(Paths.get("Characters", "plans.json"));
            Runtime.getRuntime().addShutdownHook(new Thread(value::close, "manual-plans-exit"));
            return value;
        }
    }
    public static PlanningStore shared() { return Shared.INSTANCE; }
    private void load() {
        Document loaded = new Document(); String message = path == null ? "Preview: plans stay in memory" : "Saved locally • Characters/plans.json";
        boolean failed = false;
        try {
            if (path != null && Files.exists(path)) {
                if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("Planning document is too large");
                try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonObject raw = JsonParser.parseReader(in).getAsJsonObject();
                    if (!raw.has("version") || raw.get("version").getAsInt() != 1 || !raw.has("accounts")) throw new IOException("Unsupported planning document");
                    loaded = JSON.fromJson(raw, Document.class);
                }
                if (loaded.accounts == null) throw new IOException("Missing accounts");
                for (Map.Entry<String, SavedAccount> e : loaded.accounts.entrySet()) {
                    if (e.getValue() == null || e.getValue().revision < 0) throw new IOException("Invalid account revision");
                    PlanData.validate(e.getKey(), e.getValue().plan);
                }
            }
        } catch (IOException | RuntimeException failure) {
            failed = true; loaded = new Document(); message = "Plans unavailable; existing file preserved read-only. Restore a supported backup before retrying.";
        }
        synchronized (this) { document = loaded; ready = true; readOnly = failed; status = message; }
    }
    public synchronized Snapshot snapshot(String account) {
        SavedAccount a = document.accounts.get(account);
        return new Snapshot(a == null ? 0 : a.revision, ready, readOnly || closed, status, a == null ? new PlanData.AccountPlan() : a.plan);
    }
    public synchronized Set<String> accounts() { return Collections.unmodifiableSet(new LinkedHashSet<>(document.accounts.keySet())); }
    /** Caller retains its draft for retry. The detached replacement is validated again on the writer. */
    public CompletableFuture<SaveResult> update(String account, long expectedRevision, PlanData.AccountPlan replacement) {
        final CompletableFuture<SaveResult> result = new CompletableFuture<>();
        final PlanData.AccountPlan detached;
        try { detached = PlanData.copy(replacement); PlanData.validate(account, detached); }
        catch (RuntimeException invalid) { result.complete(new SaveResult(false, invalid.getMessage(), expectedRevision)); return result; }
        synchronized (this) {
            if (closed) { result.complete(new SaveResult(false, "Planning store is closed", expectedRevision)); return result; }
            writer.execute(() -> publish(account, expectedRevision, detached, result));
        }
        return result;
    }
    private void publish(String account, long expected, PlanData.AccountPlan detached, CompletableFuture<SaveResult> result) {
        Document next; long revision;
        synchronized (this) {
            SavedAccount prior = document.accounts.get(account); revision = prior == null ? 0 : prior.revision;
            if (readOnly || !ready || revision != expected) {
                result.complete(new SaveResult(false, readOnly ? status : "Plan changed; reload before applying this draft", revision)); return;
            }
            next = JSON.fromJson(JSON.toJson(document), Document.class);
        }
        try {
            PlanData.validate(account, detached);
            SavedAccount saved = new SavedAccount(); saved.plan = detached; saved.revision = Math.addExact(revision, 1);
            next.accounts.put(account, saved);
            if (path != null) fileWriter.write(path, JSON.toJson(next));
            synchronized (this) { document = next; status = path == null ? "Preview: plans stay in memory" : "Saved locally • Characters/plans.json"; }
            result.complete(new SaveResult(true, status, saved.revision));
        } catch (IOException | RuntimeException failure) {
            synchronized (this) { status = "Save failed; draft retained. Check storage access and retry."; }
            result.complete(new SaveResult(false, status, revision));
        }
    }
    private static void writeFile(Path path, String json) throws IOException {
        Path absolute = path.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "plans-", ".tmp");
        try {
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; writer.shutdown(); }
        try { if (!writer.awaitTermination(10, TimeUnit.SECONDS)) System.err.println("Manual plans still saving; last durable file is preserved"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
