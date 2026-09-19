package util;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PreferencesStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void stalledWriterAllowsEdtReadsAndCoalescesThousandsOfChangesIntoLatestSnapshot() throws Exception {
        Path path = temp.getRoot().toPath().resolve("prefs.properties");
        writeText(path, "value=original\nunknown=keep\n");
        byte[] original = Files.readAllBytes(path);
        GatedStorage disk = new GatedStorage(false);
        PreferencesStore store = store(path, disk);
        try {
            assertTrue(store.preload().isSuccess());
            CompletionStage<PreferencesStore.SaveResult> first = store.setProperties("value", "first");
            await(disk.firstEntered);
            edt(() -> {
                CompletionStage<PreferencesStore.SaveResult> pending = store.setProperties("value", "0");
                for (int i = 1; i <= 5000; i++) {
                    assertSame("Updates share one pending batch", pending, store.setProperties("value", Integer.toString(i)));
                    assertEquals(Integer.toString(i), store.getProperty("value"));
                }
                assertSame(pending, store.flush());
            });
            // A separate EDT turn proves the heartbeat runs while I/O is still blocked.
            edt(() -> assertEquals("5000", store.getProperty("value")));
            CompletionStage<PreferencesStore.SaveResult> latest = store.flush();
            assertFalse(latest.toCompletableFuture().isDone());
            assertEquals(1, disk.writes.get());
            assertArrayEquals(original, Files.readAllBytes(path));

            disk.releaseFirst.countDown();
            await(disk.secondEntered);
            assertTrue(result(first).isSuccess());
            assertEquals(PreferencesStore.State.SAVING, store.status().state);
            assertEquals(5002, store.status().generation);
            assertEquals(1, store.status().savedGeneration);
            assertEquals("first", read(path).getProperty("value"));
            assertFalse(latest.toCompletableFuture().isDone());

            disk.releaseSecond.countDown();
            assertTrue(result(latest).isSuccess());
            assertEquals(5002, result(latest).generation);
            assertEquals(Arrays.asList("first", "5000"), disk.values);
            assertEquals("5000", read(path).getProperty("value"));
            assertEquals("keep", read(path).getProperty("unknown"));
            assertEquals(PreferencesStore.State.SAVED, store.status().state);
            assertEquals(5002, store.status().savedGeneration);
        } finally { close(store, disk); }
    }

    @Test public void failureOfOldInflightSnapshotCannotMarkNewerPendingGenerationSavedOrFailed() throws Exception {
        Path path = temp.getRoot().toPath().resolve("prefs.properties");
        writeText(path, "value=original\n");
        GatedStorage disk = new GatedStorage(true);
        PreferencesStore store = store(path, disk);
        try {
            store.preload();
            CompletionStage<PreferencesStore.SaveResult> first = store.setProperties("value", "first");
            await(disk.firstEntered);
            CompletionStage<PreferencesStore.SaveResult> latest = store.setProperties("value", "latest");
            disk.releaseFirst.countDown();
            await(disk.secondEntered);
            assertFalse(result(first).isSuccess());
            assertEquals("original", read(path).getProperty("value"));
            assertEquals(PreferencesStore.State.SAVING, store.status().state);
            assertEquals(2, store.status().generation);
            assertEquals(0, store.status().savedGeneration);
            assertTrue(store.status().detail.contains("first write failed"));
            disk.releaseSecond.countDown();
            assertTrue(result(latest).isSuccess());
            assertEquals(PreferencesStore.State.SAVED, store.status().state);
            assertEquals("latest", read(path).getProperty("value"));
        } finally { close(store, disk); }
    }

    @Test public void startupLoadsBeforeGuiAndNeitherReadersNorEarlyEditsWaitForOrLoseToDisk() throws Exception {
        Path path = temp.getRoot().toPath().resolve("prefs.properties");
        writeText(path, "fontSize=24\nunknown=preserved\nvalue=on disk\n");
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        PreferencesStore store = store(path, new PreferencesStore.FileStorage() {
            @Override public Properties read(Path file) throws IOException {
                assertFalse(SwingUtilities.isEventDispatchThread());
                reads.incrementAndGet();
                Properties original = super.read(file);
                reading.countDown();
                pause(release);
                return original;
            }
            @Override public void write(Path file, Properties snapshot) throws IOException {
                writes.incrementAndGet();
                super.write(file, snapshot);
            }
        });
        try {
            edt(() -> assertNull(store.getProperty("fontSize")));
            assertEquals("Memory reads do not trigger disk loading", 0, reads.get());
            CompletableFuture<PreferencesStore.SaveResult> startup = CompletableFuture.supplyAsync(store::preload);
            await(reading);
            edt(() -> {
                store.setProperties("value", "early edit");
                assertEquals("early edit", store.getProperty("value"));
                assertNull(store.getProperty("fontSize"));
            });
            assertEquals("No writer may race the initial read", 0, writes.get());
            release.countDown();
            assertTrue(result(startup).isSuccess());
            edt(() -> {
                assertEquals("24", store.getProperty("fontSize"));
                assertEquals("early edit", store.getProperty("value"));
                assertEquals("preserved", store.getProperty("unknown"));
            });
            assertTrue(result(store.flush()).isSuccess());
            assertEquals("early edit", read(path).getProperty("value"));
            assertEquals("preserved", read(path).getProperty("unknown"));
            assertEquals(1, reads.get());
        } finally {
            release.countDown();
            store.shutdown(2, TimeUnit.SECONDS, message -> { });
        }
    }

    @Test public void groupedUpdateIsOneGenerationAndPreservesFileReaderWriterRoundTripAndUnknownKeys() throws Exception {
        Path path = temp.getRoot().toPath().resolve("prefs.properties");
        writeText(path, "unknown=keep\nmalformedNumber=not-a-number\n");
        PreferencesStore store = new PreferencesStore(path);
        try {
            store.preload();
            Map<String, String> defaults = new HashMap<>();
            defaults.put("fontName", "Segoe UI"); defaults.put("fontStyle", "0"); defaults.put("fontSize", "13");
            PreferencesStore.SaveResult saved = result(store.setProperties(defaults));
            assertTrue(saved.isSuccess());
            assertEquals(1, saved.generation);
            defaults.put("fontSize", "changed after call");
            assertEquals("13", store.getProperty("fontSize"));
            String text = "caf\u00e9 : = \\ line\nnext";
            assertTrue(result(store.setProperties("special", text)).isSuccess());
            Properties persisted = read(path);
            assertEquals("13", persisted.getProperty("fontSize"));
            assertEquals("0", persisted.getProperty("fontStyle"));
            assertEquals("Segoe UI", persisted.getProperty("fontName"));
            assertEquals("keep", persisted.getProperty("unknown"));
            assertEquals("not-a-number", persisted.getProperty("malformedNumber"));
            assertEquals(text, persisted.getProperty("special"));
            assertTrue(result(store.flush()).isSuccess());
        } finally { store.shutdown(2, TimeUnit.SECONDS, message -> { }); }
    }

    @Test public void failedAtomicReplacePreservesOldFileCleansTempAndAllowsExplicitRetry() throws Exception {
        for (boolean unsupported : new boolean[] {false, true}) {
            Path folder = temp.newFolder().toPath(), path = folder.resolve("prefs.properties");
            writeText(path, "value=original\n");
            byte[] original = Files.readAllBytes(path);
            AtomicBoolean failing = new AtomicBoolean(true);
            PreferencesStore store = store(path, new PreferencesStore.FileStorage() {
                @Override void replace(Path temporary, Path target) throws IOException {
                    assertEquals("changed", PreferencesStoreTest.read(temporary).getProperty("value"));
                    if (failing.get()) {
                        if (unsupported) throw new AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "unsupported");
                        throw new IOException("move denied");
                    }
                    super.replace(temporary, target);
                }
            });
            try {
                store.preload();
                PreferencesStore.SaveResult failed = result(store.setProperties("value", "changed"));
                assertFalse(failed.isSuccess());
                assertEquals("changed", store.getProperty("value"));
                assertArrayEquals(original, Files.readAllBytes(path));
                assertEquals(PreferencesStore.State.FAILED, store.status().state);
                assertEquals(1, store.status().generation);
                assertEquals(0, store.status().savedGeneration);
                assertFalse(result(store.flush()).isSuccess());
                try (java.util.stream.Stream<Path> files = Files.list(folder)) { assertEquals(1, files.count()); }
                failing.set(false);
                assertTrue(result(store.setProperties("value", "changed")).isSuccess());
                assertEquals("changed", read(path).getProperty("value"));
                assertEquals(2, store.status().savedGeneration);
            } finally { store.shutdown(2, TimeUnit.SECONDS, message -> { }); }
        }
    }

    @Test public void malformedOrUnreadableFileIsPreservedAndShutdownReportsFailure() throws Exception {
        for (boolean unreadable : new boolean[] {false, true}) {
            Path path = unreadable ? temp.newFolder().toPath() : temp.newFile().toPath();
            if (!unreadable) writeText(path, "unknown=keep\nbroken=\\uZZZZ\n");
            byte[] original = unreadable ? null : Files.readAllBytes(path);
            PreferencesStore store = new PreferencesStore(path);
            List<String> failures = new ArrayList<>();
            try {
                assertFalse(store.preload().isSuccess());
                assertEquals(PreferencesStore.State.FAILED, store.status().state);
                assertFalse(result(store.setProperties("value", "memory only")).isSuccess());
                assertEquals("memory only", store.getProperty("value"));
                assertFalse(result(store.flush()).isSuccess());
                assertFalse(store.shutdown(1, TimeUnit.SECONDS, failures::add).isSuccess());
                assertEquals(1, failures.size());
                assertTrue(failures.get(0).contains("original file preserved"));
                if (unreadable) assertTrue(Files.isDirectory(path));
                else assertArrayEquals(original, Files.readAllBytes(path));
            } finally { store.shutdown(2, TimeUnit.SECONDS, message -> { }); }
        }
    }

    @Test public void missingAndEmptyFilesCanBeSaved() throws Exception {
        for (boolean empty : new boolean[] {false, true}) {
            Path path = empty ? temp.newFile().toPath() : temp.getRoot().toPath().resolve("missing.properties");
            PreferencesStore store = new PreferencesStore(path);
            try {
                assertTrue(store.preload().isSuccess());
                assertTrue(result(store.setProperties("value", "new")).isSuccess());
                assertEquals("new", read(path).getProperty("value"));
            } finally { store.shutdown(2, TimeUnit.SECONDS, message -> { }); }
        }
    }

    @Test public void shutdownReportsWriteFailureWithoutClaimingTheMemoryOnlyUpdateIsSaved() throws Exception {
        Path path = temp.getRoot().toPath().resolve("prefs.properties");
        writeText(path, "value=original\n");
        PreferencesStore store = store(path, new PreferencesStore.FileStorage() {
            @Override void replace(Path temporary, Path target) throws IOException {
                throw new IOException("move denied at shutdown");
            }
        });
        try {
            store.setProperties("value", "memory only");
            List<String> failures = new ArrayList<>();
            PreferencesStore.SaveResult shutdown = store.shutdown(2, TimeUnit.SECONDS, failures::add);
            assertFalse(shutdown.isSuccess());
            assertEquals(1, shutdown.generation);
            assertEquals(1, failures.size());
            assertTrue(failures.get(0).contains("move denied at shutdown"));
            assertEquals("memory only", store.getProperty("value"));
            assertEquals("original", read(path).getProperty("value"));
            assertEquals(PreferencesStore.State.FAILED, store.status().state);
        } finally { store.shutdown(2, TimeUnit.SECONDS, message -> { }); }
    }

    @Test public void shutdownIsBoundedReportsTimeoutAndDrainsAcceptedLatestStateWhenWriterResumes() throws Exception {
        Path path = temp.getRoot().toPath().resolve("prefs.properties");
        GatedStorage disk = new GatedStorage(false);
        PreferencesStore store = store(path, disk);
        List<String> failures = new ArrayList<>();
        try {
            store.setProperties("value", "first");
            await(disk.firstEntered);
            store.setProperties("value", "latest");
            CompletionStage<PreferencesStore.SaveResult> flush = store.flush();
            long start = System.nanoTime();
            PreferencesStore.SaveResult timedOut = store.shutdown(50, TimeUnit.MILLISECONDS, failures::add);
            assertFalse(timedOut.isSuccess());
            assertTrue("Shutdown must not wait on writer I/O", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            assertEquals(1, failures.size());
            assertTrue(failures.get(0).contains("Timed out"));
            assertEquals(2, timedOut.generation);
            assertFalse(result(store.setProperties("value", "too late")).isSuccess());
            edt(() -> assertEquals("latest", store.getProperty("value")));
            disk.releaseFirst.countDown();
            await(disk.secondEntered);
            assertFalse(flush.toCompletableFuture().isDone());
            disk.releaseSecond.countDown();
            assertTrue(result(flush).isSuccess());
            assertTrue(store.shutdown(1, TimeUnit.SECONDS, failures::add).isSuccess());
            assertEquals(1, failures.size());
            assertEquals("latest", read(path).getProperty("value"));
        } finally { close(store, disk); }
    }

    @Test public void successfulShutdownWaitsForBothInflightAndPendingAndDoesNotReportFailure() throws Exception {
        Path path = temp.getRoot().toPath().resolve("prefs.properties");
        GatedStorage disk = new GatedStorage(false);
        PreferencesStore store = store(path, disk);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            store.setProperties("value", "first");
            await(disk.firstEntered);
            store.setProperties("value", "latest");
            CompletableFuture<PreferencesStore.SaveResult> shutdown = CompletableFuture.supplyAsync(() ->
                store.shutdown(5, TimeUnit.SECONDS, failures::add));
            disk.releaseFirst.countDown();
            await(disk.secondEntered);
            assertFalse(shutdown.isDone());
            disk.releaseSecond.countDown();
            assertTrue(result(shutdown).isSuccess());
            assertTrue(failures.isEmpty());
            assertEquals("latest", read(path).getProperty("value"));
        } finally { close(store, disk); }
    }

    @Test public void blockingStartupAndShutdownAreRejectedOnEdtButFlushIsNonblocking() throws Exception {
        PreferencesStore store = new PreferencesStore(temp.getRoot().toPath().resolve("prefs.properties"));
        try {
            edt(() -> {
                assertThrows(IllegalStateException.class, store::preload);
                assertThrows(IllegalStateException.class, () -> store.shutdown(1, TimeUnit.SECONDS, message -> { }));
                assertNotNull(store.flush());
            });
            assertTrue(result(store.flush()).isSuccess());
        } finally { store.shutdown(2, TimeUnit.SECONDS, message -> { }); }
    }

    private static PreferencesStore store(Path path, PreferencesStore.Storage storage) {
        return new PreferencesStore(path, new Properties(), storage);
    }

    private static PreferencesStore.SaveResult result(CompletionStage<PreferencesStore.SaveResult> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void edt(Runnable action) throws Exception {
        CompletableFuture<Void> turn = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try { action.run(); turn.complete(null); }
            catch (Throwable failure) { turn.completeExceptionally(failure); }
        });
        turn.get(3, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("Worker did not reach the controlled I/O boundary", latch.await(5, TimeUnit.SECONDS));
    }

    private static void pause(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IOException("Test did not release storage");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }

    private static void close(PreferencesStore store, GatedStorage disk) {
        disk.releaseFirst.countDown(); disk.releaseSecond.countDown();
        store.shutdown(2, TimeUnit.SECONDS, message -> { });
    }

    private static void writeText(Path path, String text) throws IOException {
        try (FileWriter writer = new FileWriter(path.toFile())) { writer.write(text); }
    }

    private static Properties read(Path path) throws IOException {
        Properties properties = new Properties();
        try (FileReader reader = new FileReader(path.toFile())) { properties.load(reader); }
        return properties;
    }

    private static final class GatedStorage extends PreferencesStore.FileStorage {
        final CountDownLatch firstEntered = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1), releaseSecond = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        final List<String> values = Collections.synchronizedList(new ArrayList<>());
        final boolean failFirst;

        GatedStorage(boolean failFirst) { this.failFirst = failFirst; }

        @Override public void write(Path path, Properties snapshot) throws IOException {
            assertFalse(SwingUtilities.isEventDispatchThread());
            int write = writes.incrementAndGet();
            values.add(snapshot.getProperty("value"));
            if (write == 1) {
                firstEntered.countDown(); pause(releaseFirst);
                if (failFirst) throw new IOException("first write failed");
            } else if (write == 2) {
                secondEntered.countDown(); pause(releaseSecond);
            }
            super.write(path, snapshot);
        }
    }
}
