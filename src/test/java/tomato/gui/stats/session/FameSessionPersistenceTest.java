package tomato.gui.stats.session;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.stats.Fame;
import tomato.gui.stats.data.MapFameData;
import static org.junit.Assert.*;

public class FameSessionPersistenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void blockedAutosaveKeepsEdtFreeCoalescesAndDetachesOpenMapRows() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(4);
        List<FameSession> saved = Collections.synchronizedList(new ArrayList<>());
        FameSessionManager.SessionWriter writer = new FameSessionManager.SessionWriter((file, json) -> {
            if (saved.isEmpty()) { entered.countDown(); await(release); }
            saved.add(new Gson().fromJson(json, FameSession.class));
        });
        ConcurrentLinkedQueue<Boolean> results = new ConcurrentLinkedQueue<>();
        java.util.function.Consumer<Boolean> completion = success -> {
            results.add(success && SwingUtilities.isEventDispatchThread()); completed.countDown();
        };
        FameSession session = session("Original");
        long created = session.getCreatedTimestamp();
        File firstFile = new File(temp.getRoot(), "first.fame"), nextFile = new File(temp.getRoot(), "next.fame");
        try {
            SwingUtilities.invokeAndWait(() -> writer.save(FameSessionManager.snapshot(session), firstFile, completion));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> edt = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> {
                try {
                    MapFameData open = session.getCharacterMapFameData().get(1).get(1);
                    open.endFame = 120; open.endTime = 3000;
                    writer.save(FameSessionManager.snapshot(session), firstFile, completion);
                    session.getCharacterFameData().get(1).add(new Fame(130, 4000));
                    open.endFame = 130; open.endTime = 4000;
                    writer.save(FameSessionManager.snapshot(session), firstFile, completion);
                    // Neither later mutations nor a session boundary may change pending bytes.
                    open.mapName = "Mutated after snapshot"; open.endFame = 999;
                    session.getCharacterFameData().get(1).clear();
                    writer.save(FameSessionManager.snapshot(session("Next")), nextFile, completion);
                    edt.complete(null);
                } catch (Throwable e) { edt.completeExceptionally(e); }
            });
            edt.get(2, TimeUnit.SECONDS);
            release.countDown();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(true, true, true, true), new ArrayList<>(results));
            assertEquals(3, saved.size()); // first in flight + latest for that file + next session
            assertEquals(110, saved.get(0).getCharacterMapFameData().get(1).get(1).endFame, 0);
            FameSession coalesced = saved.get(1);
            assertEquals("Original", coalesced.getSessionName());
            assertEquals(created, coalesced.getCreatedTimestamp());
            assertEquals("Wizard", coalesced.getCharacterClassNames().get(1));
            assertEquals(3, coalesced.getCharacterFameData().get(1).size());
            assertEquals(2, coalesced.getCharacterMapFameData().get(1).size());
            assertEquals(0, coalesced.getCharacterMapFameData().get(1).get(0).getFameGained(), 0);
            MapFameData open = coalesced.getCharacterMapFameData().get(1).get(1);
            assertEquals("Lost Halls", open.mapName); assertEquals(4000, open.endTime); assertEquals(130, open.endFame, 0);
            assertEquals("Next", saved.get(2).getSessionName());
        } finally { release.countDown(); writer.close(); }
    }

    @Test public void deleteIsOrderedAfterInFlightAndPendingSaves() throws Exception {
        File file = new File(temp.getRoot(), "delete.fame");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), deleted = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        FameSessionManager.SessionWriter writer = new FameSessionManager.SessionWriter((destination, json) -> {
            if (writes.incrementAndGet() == 1) { entered.countDown(); await(release); }
            Files.write(destination.toPath(), json.getBytes(StandardCharsets.UTF_8));
        });
        try {
            writer.save(FameSessionManager.snapshot(session("Delete")), file, null);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            writer.save(FameSessionManager.snapshot(session("Pending")), file, null);
            writer.delete(file, success -> { if (success) deleted.countDown(); });
            release.countDown();
            assertTrue(deleted.await(3, TimeUnit.SECONDS));
            writer.close();
            assertFalse(file.exists());
            assertEquals(2, writes.get());
        } finally { release.countDown(); writer.close(); }
    }

    @Test public void failedWriteReportsFailureAndDoesNotStrandLaterSaves() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1), succeeded = new CountDownLatch(1);
        FameSessionManager.SessionWriter writer = new FameSessionManager.SessionWriter((file, json) -> {
            if (writes.incrementAndGet() == 1) throw new IOException("Simulated disk failure");
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        });
        File file = new File(temp.getRoot(), "retry.fame");
        try {
            writer.save(FameSessionManager.snapshot(session("Retry")), file, success -> { if (!success) failed.countDown(); });
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            writer.save(FameSessionManager.snapshot(session("Retry")), file, success -> { if (success) succeeded.countDown(); });
            assertTrue(succeeded.await(3, TimeUnit.SECONDS));
            FameSession loaded = FameSessionManager.loadSession(file);
            assertNotNull(loaded); assertEquals("Retry", loaded.getSessionName());
            assertEquals(2, loaded.getCharacterMapFameData().get(1).size());
        } finally { writer.close(); }
    }

    @Test public void legacyHistoryRoundTripPreservesMetadataFlatNegativeAndDuplicateTimeSamples() throws Exception {
        String legacy = "{\"sessionName\":\"Step1 history\",\"createdTimestamp\":123,\"lastModifiedTimestamp\":456,"
            + "\"description\":\"Retained history\",\"readOnly\":false,\"characterClassNames\":{\"1\":\"Wizard\"},"
            + "\"characterFameData\":{\"1\":[{\"fame\":100,\"time\":1000},{\"fame\":100,\"time\":2000},"
            + "{\"fame\":95,\"time\":2000}]},\"characterMapFameData\":{\"1\":["
            + "{\"mapName\":\"Nexus\",\"startTime\":1000,\"endTime\":2000,\"startFame\":100,\"endFame\":100},"
            + "{\"mapName\":\"Realm\",\"startTime\":2000,\"endTime\":3000,\"startFame\":100,\"endFame\":95}]}}";
        File source = temp.newFile("legacy.fame"), destination = new File(temp.getRoot(), "roundtrip.fame");
        Files.write(source.toPath(), legacy.getBytes(StandardCharsets.UTF_8));
        FameSession loaded = FameSessionManager.loadSession(source);
        assertNotNull(loaded);
        try (FameSessionManager.SessionWriter writer = new FameSessionManager.SessionWriter((file, json) ->
                Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8)))) {
            assertTrue(writer.save(FameSessionManager.snapshot(loaded), destination, null).get(10, TimeUnit.SECONDS));
        }
        FameSession saved = FameSessionManager.loadSession(destination);
        assertNotNull(saved); assertEquals("Step1 history", saved.getSessionName());
        assertEquals(123, saved.getCreatedTimestamp()); assertEquals(456, saved.getLastModifiedTimestamp());
        assertEquals("Retained history", saved.getDescription()); assertEquals("Wizard", saved.getCharacterClassNames().get(1));
        assertEquals(Arrays.asList(new Fame(100, 1000), new Fame(100, 2000), new Fame(95, 2000)), saved.getCharacterData(1));
        assertEquals(2, saved.getCharacterMapFameData().get(1).size());
        assertEquals(0, saved.getCharacterMapFameData().get(1).get(0).getFameGained(), 0);
        assertEquals(-5, saved.getCharacterMapFameData().get(1).get(1).getFameGained(), 0);
    }

    private static FameSession session(String name) {
        FameSession session = new FameSession(name);
        session.addCharacterData(1, "Wizard", Arrays.asList(new Fame(100, 1000), new Fame(110, 2000)));
        MapFameData zero = new MapFameData("Nexus", 500, 100); zero.endTime = 1000;
        MapFameData open = new MapFameData("Lost Halls", 1000, 100); open.endTime = 2000; open.endFame = 110;
        session.addCharacterMapData(1, Arrays.asList(zero, open));
        return session;
    }

    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test writer was not released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
}
