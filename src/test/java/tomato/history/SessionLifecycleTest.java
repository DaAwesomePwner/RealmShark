package tomato.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatRetention;
import tomato.history.encounter.CombatSettings;
import tomato.history.encounter.CombatFacts;
import util.AtomicFiles;
import static org.junit.Assert.*;

public class SessionLifecycleTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String SOURCE = "synthetic-lifecycle";
    private static final String ID = UUID.nameUUIDFromBytes(SOURCE.getBytes(StandardCharsets.UTF_8)).toString();
    private static final long DAY = 86_400_000L, NOW = 1_800_000_000_000L;

    @Test public void notificationsFollowWritesAndIgnoreNoOpsAndRefusedOperations() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test")) {
            List<String> events = new ArrayList<>();
            List<Boolean> persisted = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            SessionStore.LifecycleListener listener = new SessionStore.LifecycleListener() {
                @Override public void sessionRemoved(String id) {
                    events.add("removed:" + id); threads.add(Thread.currentThread());
                    persisted.add(!Files.exists(store.directory().resolve(id)));
                }
                @Override public void sessionChanged(String id) {
                    events.add("changed:" + id); threads.add(Thread.currentThread());
                    persisted.add(Files.isRegularFile(store.directory().resolve(id).resolve("session.json")));
                }
            };
            store.setLifecycleListener(listener);
            assertThrows(IllegalStateException.class, () -> store.setLifecycleListener(listener));
            importCombat(store);
            assertEquals(List.of("changed:" + ID), events);
            importCombat(store); // Import marker: no new writes.
            assertEquals(0, store.deleteFiles(ID, CombatFacts.RECORDS, file -> false));
            assertEquals(0, store.deleteFiles(ID, "missing", file -> true));
            assertThrows(IOException.class, () -> store.delete(store.currentId()));
            assertThrows(IOException.class, () -> store.rename(store.currentId(), "Refused"));
            assertEquals(1, events.size());
            store.rename(ID, "Renamed");
            assertEquals("Renamed", metadata(store).label);
            assertEquals(1, store.deleteFiles(ID, CombatFacts.RECORDS, file -> true));
            store.delete(ID); store.delete(ID);
            assertEquals(List.of("changed:" + ID, "changed:" + ID, "changed:" + ID, "removed:" + ID), events);
            assertTrue(persisted.stream().allMatch(Boolean::booleanValue));
            assertTrue(threads.stream().allMatch(thread -> thread == Thread.currentThread()));
        }
    }

    @Test public void failedDeleteNotifiesOnlyAfterPartialDeletionAndCanBeRetried() throws Exception {
        for (int failAfter : new int[]{0, 1}) {
            try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test")) {
                importCombat(store);
                Path folder = store.directory().resolve(ID);
                Path first = Files.writeString(folder.resolve("zzz.jsonl"), "first");
                Path locked = Files.writeString(folder.resolve("yyy.jsonl"), "locked");
                List<String> events = new ArrayList<>();
                List<Boolean> folderPresent = new ArrayList<>();
                store.setLifecycleListener(new SessionStore.LifecycleListener() {
                    @Override public void sessionRemoved(String id) {
                        events.add("removed:" + id); folderPresent.add(Files.isDirectory(folder));
                    }
                    @Override public void sessionChanged(String id) {
                        events.add("changed:" + id); folderPresent.add(Files.isDirectory(folder));
                        throw new IllegalStateException("synthetic listener failure");
                    }
                });
                IOException failure = new IOException("synthetic locked file");
                List<Path> deleted = new ArrayList<>();
                assertSame(failure, assertThrows(IOException.class, () -> store.delete(ID, file -> {
                    if (deleted.size() == failAfter) throw failure;
                    Files.delete(file); deleted.add(file);
                })));
                assertEquals(failAfter, deleted.size());
                assertEquals(failAfter == 0, Files.exists(first));
                assertTrue(Files.exists(locked));
                assertEquals("Imported", metadata(store).label);
                assertEquals(failAfter == 0 ? List.of() : List.of("changed:" + ID), events);
                assertEquals(failAfter == 0 ? List.of() : List.of(true), folderPresent);
                events.clear(); folderPresent.clear();
                store.delete(ID);
                assertFalse(Files.exists(folder));
                assertEquals(List.of("removed:" + ID), events);
                assertEquals(List.of(false), folderPresent);
            }
        }
    }

    @Test public void absentAndThrowingListenersPreserveImportRenamePruneAndDeleteResults() throws Exception {
        for (Throwable failure : new Throwable[]{null, new IllegalStateException("synthetic"),
                new LinkageError("synthetic"), new StackOverflowError("synthetic")}) {
            try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test")) {
                List<String> events = new ArrayList<>();
                if (failure != null) store.setLifecycleListener(new SessionStore.LifecycleListener() {
                    private void failed(String event) {
                        events.add(event);
                        if (failure instanceof Error error) throw error;
                        throw (RuntimeException) failure;
                    }
                    @Override public void sessionRemoved(String id) { failed("removed"); }
                    @Override public void sessionChanged(String id) { failed("changed"); }
                });
                importCombat(store);
                assertEquals(1, store.read(ID, CombatFacts.RECORDS, Map.class).size());
                store.rename(ID, "Still saved");
                assertEquals("Still saved", metadata(store).label);
                CombatRetention.Result result = CombatRetention.prune(store,
                    new CombatSettings.Values(false, 30, 90), NOW, new Cancellation());
                assertEquals(1, result.files()); assertEquals(0, result.sessionsSkipped());
                assertTrue(store.read(ID, CombatFacts.RECORDS, Map.class).isEmpty());
                store.delete(ID);
                assertFalse(Files.exists(store.directory().resolve(ID)));
                if (failure != null) assertEquals(List.of("changed", "changed", "changed", "removed"), events);
                assertEquals("", store.error());
            }
        }
    }

    @Test public void partiallyWrittenImportStillNotifiesAndPreservesTheIoFailure() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test",
                file -> java.nio.channels.FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                (file, bytes, sync) -> {
                    if (file.getParent().getFileName().toString().equals(CombatFacts.RECORDS)) throw new IOException("synthetic item failure");
                    AtomicFiles.write(file, bytes, sync);
                })) {
            List<String> changed = new ArrayList<>();
            store.setLifecycleListener(new SessionStore.LifecycleListener() {
                @Override public void sessionRemoved(String id) { }
                @Override public void sessionChanged(String id) { changed.add(id); throw new IllegalStateException("listener failure"); }
            });
            assertEquals("synthetic item failure", assertThrows(IOException.class, () -> importCombat(store)).getMessage());
            assertEquals(List.of(ID), changed);
            assertEquals("Imported", metadata(store).label);
            assertTrue(store.read(ID, CombatFacts.RECORDS, Map.class).isEmpty());
        }
    }

    @Test public void fatalVmListenerFailurePropagatesAfterTheWrite() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test")) {
            InternalError failure = new InternalError("synthetic fatal VM failure");
            store.setLifecycleListener(new SessionStore.LifecycleListener() {
                @Override public void sessionRemoved(String id) { throw failure; }
                @Override public void sessionChanged(String id) { throw failure; }
            });
            assertSame(failure, assertThrows(InternalError.class, () -> importCombat(store)));
            assertEquals(1, store.read(ID, CombatFacts.RECORDS, Map.class).size());
            assertSame(failure, assertThrows(InternalError.class, () -> store.delete(ID)));
            assertFalse(Files.exists(store.directory().resolve(ID)));
        }
    }

    private static void importCombat(SessionStore store) throws IOException {
        store.importSnapshot(SOURCE, "Imported", NOW - 400 * DAY, CombatFacts.RECORDS, "old",
            Map.of("recordingId", "old", "enteredAt", NOW - 400 * DAY));
    }
    private static SessionStore.Session metadata(SessionStore store) throws IOException {
        return SessionStore.JSON.fromJson(Files.readString(store.directory().resolve(ID).resolve("session.json")), SessionStore.Session.class);
    }
}
