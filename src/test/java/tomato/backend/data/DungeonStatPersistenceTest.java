package tomato.backend.data;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DungeonStatPersistenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void errorCompletesFlushAndLaterSavesAreScheduledEvenIfDiagnosticsFails() throws Exception {
        Path path = temp.getRoot().toPath().resolve("error.stats");
        AtomicBoolean failing = new AtomicBoolean(true); AtomicInteger writes = new AtomicInteger(), reports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        DungeonStatData data = new DungeonStatData(path, (file, json) -> {
            writes.incrementAndGet();
            if (failing.get()) throw new StackOverflowError("synthetic serializer failure");
            Files.writeString(file, json);
        }, file -> Files.newBufferedReader(file), (message, failure) -> {
            reports.incrementAndGet(); reported.set(failure);
            throw new AssertionError("synthetic diagnostic failure");
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            visit(data);
            // The production flush timeout is three seconds: completion within two rules out a wedged writer.
            assertFalse(caller.submit(() -> data.flush()).get(2, TimeUnit.SECONDS));
            assertTrue(writes.get() > 0); assertEquals(1, reports.get());
            assertTrue(reported.get() instanceof StackOverflowError);
            assertTrue(data.storageStatus().contains("Save failed"));
            failing.set(false); visit(data);
            assertTrue(caller.submit(() -> data.flush()).get(2, TimeUnit.SECONDS));
            DungeonStatData reopened = new DungeonStatData(path); reopened.load();
            assertEquals(2, reopened.snapshot().get(0).visits); assertEquals(1, reports.get());
        } finally { caller.shutdownNow(); }
    }
    @Test public void loadAndSaveDiagnosticsAreReportedOnlyOnFailureTransitions() throws Exception {
        Path path = temp.getRoot().toPath().resolve("transitions.stats");
        AtomicBoolean loadFails = new AtomicBoolean(true), saveFails = new AtomicBoolean(true);
        java.util.List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
        DungeonStatData data = new DungeonStatData(path, (file, json) -> {
            if (saveFails.get()) throw new IOException("synthetic write failure");
            Files.writeString(file, json);
        }, file -> {
            if (loadFails.get()) throw new IOException("synthetic read failure");
            return new java.io.StringReader("{\"data\":{}}");
        }, (message, failure) -> reports.add(message));
        assertThrows(RuntimeException.class, data::load);
        assertThrows(RuntimeException.class, data::load);
        visit(data); assertFalse(data.flush());
        assertEquals(1, reports.size()); assertTrue(reports.get(0).contains("Cannot read"));
        loadFails.set(false); data.load();
        visit(data); assertFalse(data.flush()); assertFalse(data.flush());
        assertEquals(2, reports.size()); assertTrue(reports.get(1).contains("Save failed"));
        saveFails.set(false); assertTrue(data.flush());
        saveFails.set(true); visit(data); assertFalse(data.flush());
        assertEquals(3, reports.size()); assertTrue(reports.get(2).contains("Save failed"));
    }
    @Test public void captureReturnsBeforeFailingIoAndFailureCanBeRetried() throws Exception {
        Path path = temp.getRoot().toPath().resolve("dungeon.stats");
        Thread caller = Thread.currentThread(); AtomicBoolean fail = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DungeonStatData data = new DungeonStatData(path, (file, json) -> {
            assertNotSame(caller, Thread.currentThread()); assertTrue(Thread.currentThread().isDaemon());
            entered.countDown(); await(release);
            if (fail.get()) throw new IOException("disk full");
            Files.writeString(file, json);
        });
        try {
            visit(data); // Must return while the writer is waiting below.
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, data.snapshot().get(0).visits);
            release.countDown(); assertFalse(data.flush());
            assertTrue(data.storageStatus().contains("Save failed")); assertFalse(Files.exists(path));
            fail.set(false); assertTrue(data.flush());
            DungeonStatData reopened = new DungeonStatData(path); reopened.load();
            assertEquals(1, reopened.snapshot().get(0).visits);
        } finally { release.countDown(); }
    }
    @Test public void manyPendingExitsCoalesceBehindOneWriterAndFlushPersistsLatest() throws Exception {
        Path path = temp.getRoot().toPath().resolve("dungeon.stats"); AtomicInteger writes = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DungeonStatData data = new DungeonStatData(path, (file, json) -> {
            if (writes.incrementAndGet() == 1) { entered.countDown(); await(release); }
            Files.writeString(file, json);
        });
        try {
            visit(data); assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) visit(data);
            assertEquals(1, writes.get());
            release.countDown(); assertTrue(data.flush());
            assertTrue("Only the latest pending snapshot (plus final sync) is saved", writes.get() <= 3);
            DungeonStatData reopened = new DungeonStatData(path); reopened.load();
            assertEquals(101, reopened.snapshot().get(0).visits);
        } finally { release.countDown(); }
    }
    @Test public void flushWaitIsBoundedAndWriterCanFinishAfterTimeout() throws Exception {
        Path path = temp.getRoot().toPath().resolve("dungeon.stats");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DungeonStatData data = new DungeonStatData(path, (file, json) -> {
            entered.countDown(); await(release); Files.writeString(file, json);
        });
        try {
            visit(data); assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            assertFalse(data.flush(50, TimeUnit.MILLISECONDS));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            release.countDown(); assertTrue(data.flush()); assertTrue(Files.exists(path));
        } finally { release.countDown(); }
    }
    private static void visit(DungeonStatData data) {
        Entity mob = new Entity(null, 1, 0); mob.objectType = 100;
        data.updateEntityDamage("Dungeon", mob); data.updateDungeon("Dungeon", 200);
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Writer gate timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
    }
}
