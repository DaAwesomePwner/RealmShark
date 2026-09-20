package packets.packetcapture.logger;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Coalesced, atomic local checkpoint; capture never waits for filesystem writes. */
final class ActivityStore implements AutoCloseable {
    private final Path file;
    private final AtomicReference<Checkpoint> pending = new AtomicReference<>();
    private final Thread worker;
    private volatile boolean closed;
    private volatile String error = "";
    private boolean preserveUnreadable;
    ActivityStore(Path directory) {
        file = directory.resolve("activity-history.json");
        worker = new Thread(this::run, "RealmShark activity history writer");
        worker.setDaemon(true); worker.start();
    }
    ActivityJournal.State load() {
        if (!Files.exists(file)) return null;
        try {
            // Retained dungeon rosters include equipment/enchant snapshots as well as activity charts.
            if (Files.size(file) > 128 * 1024 * 1024) throw new IllegalArgumentException();
            ActivityJournal.State state = new Gson().fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), ActivityJournal.State.class);
            if (state == null || state.schemaVersion != 1 || state.visits == null || state.entries == null
                || state.visits.size() > ActivityJournal.RUN_LIMIT || state.entries.size() > ActivityJournal.EVENT_LIMIT
                || state.visits.contains(null) || state.entries.contains(null)) throw new IllegalArgumentException();
            for (ActivityJournal.Visit v : state.visits)
                if (v.id == null || v.map == null || v.conditions == null || v.extraConditions == null
                    || v.requestedItems == null || v.effects == null || v.equipment == null || v.resourceTimeline == null
                    || v.conditionTimeline == null || v.resourceTimeline.contains(null) || v.conditionTimeline.contains(null)
                    || v.inspectedPlayers == null || v.inspectedPlayers.size() > ActivityJournal.INSPECT_PLAYER_LIMIT
                    || v.playerDamage == null || v.playerDamage.size() > ActivityJournal.INSPECT_PLAYER_LIMIT
                    || v.playerDamage.values().stream().anyMatch(d -> d == null || d < 0)
                    || v.completionEvidence == null || v.endReason == null
                    || v.inspectedPlayers.values().stream().anyMatch(p -> p == null || !p.isValid())) throw new IllegalArgumentException();
            for (ActivityJournal.Entry e : state.entries)
                if (e.values == null || e.visitId == null || e.map == null || e.kind == null || e.detail == null) throw new IllegalArgumentException();
            return state;
        } catch (Exception e) {
            preserveUnreadable = true;
            error = "Saved activity history could not be read; it will be preserved before writing new history."; return null;
        }
    }
    void offer(ActivityJournal.State state) { offer(() -> state); }
    void offer(Supplier<ActivityJournal.State> snapshot) { offer(snapshot,()->{}); }
    void offer(Supplier<ActivityJournal.State> snapshot,Runnable persisted) {
        if (!closed) pending.set(new Checkpoint(snapshot,persisted));
    }
    String error() { return error; }
    private void run() {
        while (!closed || pending.get() != null) {
            Checkpoint checkpoint = pending.getAndSet(null);
            if (checkpoint == null) {
                try { Thread.sleep(200); } catch (InterruptedException e) { /* Recheck shutdown. */ }
                continue;
            }
            try {
                ActivityJournal.State state = checkpoint.snapshot.get();
                Files.createDirectories(file.getParent());
                if (preserveUnreadable && Files.exists(file)) {
                    Files.move(file, file.resolveSibling("activity-history-unreadable-" + System.currentTimeMillis() + ".json"));
                    preserveUnreadable = false;
                }
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(temp, new Gson().toJson(state).getBytes(StandardCharsets.UTF_8));
                try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
                error = "";
                checkpoint.persisted.run(); // All serialization and filesystem I/O has finished.
            } catch (Exception e) { error = "Activity history could not be saved. Check folder permissions and free space."; }
        }
    }
    private static final class Checkpoint {
        final Supplier<ActivityJournal.State> snapshot;
        final Runnable persisted;
        Checkpoint(Supplier<ActivityJournal.State> snapshot,Runnable persisted) { this.snapshot=snapshot; this.persisted=persisted; }
    }
    @Override public void close() {
        closed = true;
        try { worker.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
