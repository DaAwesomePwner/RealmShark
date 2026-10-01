package tomato.history;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import util.AtomicFiles;
import static org.junit.Assert.*;

public class SessionWriteTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static FileChannel open(Path file) throws IOException {
        return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
    private static Path journal(SessionStore store, String module) {
        return store.directory().resolve(store.currentId()).resolve(module + ".jsonl");
    }
    private static final class Hold implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        Hold(SessionStore store) throws Exception {
            Field field = SessionStore.class.getDeclaredField("worker"); field.setAccessible(true);
            CountDownLatch entered = new CountDownLatch(1);
            ((ExecutorService)field.get(store)).submit(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            if (!entered.await(5, TimeUnit.SECONDS)) { release.countDown(); fail("Writer did not reach barrier"); }
        }
        @Override public void close() { release.countDown(); }
    }
    @Test public void interleavedModulesUseOneOpenEachAndKeepExactJsonLinesAndOrder() throws Exception {
        Map<Path, AtomicInteger> opens = new ConcurrentHashMap<>();
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", file -> {
            opens.computeIfAbsent(file, key -> new AtomicInteger()).incrementAndGet(); return open(file);
        });
        try {
            Map<String, StringBuilder> expected = new LinkedHashMap<>();
            try (Hold hold = new Hold(store)) {
                for (int i = 0; i < 1500; i++) {
                    String module = i % 2 == 0 ? "chat" : "timeline";
                    Map<String, Object> value = new LinkedHashMap<>(); value.put("index", i); value.put("text", "Unicode \u96ea\n\"quoted\"");
                    expected.computeIfAbsent(module, key -> new StringBuilder()).append(SessionStore.JSON.toJson(value)).append('\n');
                    store.append(module, value);
                }
                store.put("runs", "one", Map.of("final", true));
            }
            store.flush();
            for (Map.Entry<String, StringBuilder> entry : expected.entrySet()) {
                Path file = journal(store, entry.getKey());
                assertEquals(entry.getValue().toString(), Files.readString(file, StandardCharsets.UTF_8));
                assertEquals("One channel per module in the drain", 1, opens.get(file).get());
            }
            assertEquals(1, store.read(store.currentId(), "runs", JsonObject.class).size());
        } finally { store.close(); }
    }
    @Test public void partialBatchFailureRollsBackAndRetryDoesNotDuplicateEvenIfRollbackFailsOnce() throws Exception {
        AtomicBoolean failWrite = new AtomicBoolean(), failRollback = new AtomicBoolean();
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", file ->
            file.getFileName().toString().equals("chat.jsonl") ? new FailingChannel(open(file), failWrite, failRollback) : open(file));
        try {
            store.append("chat", "prefix"); store.flush();
            String prefix = Files.readString(journal(store, "chat"));
            try (Hold hold = new Hold(store)) {
                failWrite.set(true); failRollback.set(true);
                store.append("timeline", "committed before failure");
                store.append("chat", "first"); store.append("chat", "second");
            }
            try { store.flush(); fail("Expected IO failure"); } catch (IOException expected) { }
            assertTrue(store.error(), store.error().startsWith(SessionStore.SAVE_FAILED));
            // A subsequent retry restores the original prefix before attempting another batch.
            try { store.flush(); fail("Expected IO failure"); } catch (IOException expected) { }
            try (Hold hold = new Hold(store)) {
                assertEquals(prefix, Files.readString(journal(store, "chat")));
                failWrite.set(false);
            }
            store.flush();
            assertEquals(prefix + "\"first\"\n\"second\"\n", Files.readString(journal(store, "chat")));
            assertEquals(List.of("committed before failure"), store.read(store.currentId(), "timeline", String.class));
            assertEquals("", store.error());
        } finally { failWrite.set(false); failRollback.set(false); store.close(); }
    }
    @Test public void poisonEventsAndCheckpointsAreSkippedCountedAndDoNotBlockLaterWrites() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test");
        try {
            try (Hold hold = new Hold(store)) {
                store.append("chat", "before");
                store.append("chat", new Bad(0)); store.append("chat", new Bad(1)); store.append("chat", new Bad(2));
                store.append("chat", "after"); store.put("notes", "bad", new Bad(0)); store.put("notes", "good", "saved");
            }
            store.flush();
            assertEquals(List.of("before", "after"), store.read(store.currentId(), "chat", String.class));
            assertEquals(List.of("saved"), store.read(store.currentId(), "notes", String.class));
            assertEquals("4" + SessionStore.RECORDS_SKIPPED, store.error());
            store.flush(); assertEquals("4" + SessionStore.RECORDS_SKIPPED, store.error());
        } finally { store.close(); }
    }
    @Test public void fullQueueRefusesNewEventsButKeepsCheckpointsAndReportsCurrentIoFailure() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", file -> {
            if (fail.get()) throw new IOException("synthetic disk failure"); return open(file);
        });
        try {
            try (Hold hold = new Hold(store)) {
                for (int i = 0; i < SessionStore.EVENT_LIMIT + 3; i++) store.append("chat", i);
                store.put("notes", "one", "checkpoint");
            }
            try { store.flush(); fail("Expected IO failure"); } catch (IOException expected) { }
            assertTrue(store.error(), store.error().startsWith(SessionStore.SAVE_FAILED));
            assertTrue(store.error(), store.error().endsWith("3" + SessionStore.RECORDS_SKIPPED));
            fail.set(false); store.flush();
            List<Integer> saved = store.read(store.currentId(), "chat", Integer.class);
            assertEquals(SessionStore.EVENT_LIMIT, saved.size());
            for (int i = 0; i < saved.size(); i++) assertEquals(Integer.valueOf(i), saved.get(i));
            assertEquals(List.of("checkpoint"), store.read(store.currentId(), "notes", String.class));
            assertEquals("3" + SessionStore.RECORDS_SKIPPED, store.error());
        } finally { fail.set(false); store.close(); }
    }
    @Test public void serializationIoFailuresAreSkippedCountedAndNeverRetried() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test");
        Bad value = new Bad(3);
        try {
            try (Hold hold = new Hold(store)) {
                store.append("chat", value); store.append("chat", "after"); store.put("notes", "good", "saved");
            }
            store.flush();
            assertEquals("1" + SessionStore.RECORDS_SKIPPED, store.error());
            assertEquals(List.of("after"), store.read(store.currentId(), "chat", String.class));
            assertEquals(List.of("saved"), store.read(store.currentId(), "notes", String.class));
            value.retryReady.set(true); store.flush();
            assertEquals(List.of("after"), store.read(store.currentId(), "chat", String.class));
            assertEquals("1" + SessionStore.RECORDS_SKIPPED, store.error());
        } finally { store.close(); }
    }
    @Test public void closeSyncsFinalCheckpointsAndMetadataAndForcesEachTouchedJournalAfterItsLastAppend() throws Exception {
        List<AtomicSave> saves = new CopyOnWriteArrayList<>();
        Map<Path, AtomicInteger> forces = new ConcurrentHashMap<>();
        Map<Path, String> forcedContents = new ConcurrentHashMap<>();
        List<String> order = new CopyOnWriteArrayList<>();
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", file ->
            new FailingChannel(open(file), new AtomicBoolean(), new AtomicBoolean(), metadata -> {
                assertEquals("RealmShark session history", Thread.currentThread().getName());
                assertTrue(metadata);
                forces.computeIfAbsent(file, key -> new AtomicInteger()).incrementAndGet();
                forcedContents.put(file, Files.readString(file, StandardCharsets.UTF_8));
                order.add("force");
            }), (file, bytes, sync) -> {
                assertEquals("RealmShark session history", Thread.currentThread().getName());
                saves.add(new AtomicSave(file, bytes, sync));
                AtomicFiles.write(file, bytes, sync);
                order.add(file.getFileName().toString());
            });
        try {
            store.append("chat", "first"); store.append("early", "only before close");
            store.put("runs", "final", Map.of("text", "routine \u96ea")); store.flush();
            store.append("chat", "second"); store.flush();
            assertFalse(saves.isEmpty());
            assertTrue(saves.stream().noneMatch(AtomicSave::sync));
            assertTrue(forces.isEmpty());
            Path folder = store.directory().resolve(store.currentId());
            Files.writeString(folder.resolve("untouched.jsonl"), "\"not appended by the store\"\n");
            List<String> modules = List.of("runs", "fame-latest", "dungeon-totals", "combat");
            Map<String, String> finalValue = Map.of("text", "final \u96ea\n\"quoted\"");
            AtomicBoolean finalCollected = new AtomicBoolean();
            store.collect("final", () -> {
                if (!store.closing() || !finalCollected.compareAndSet(false, true)) return;
                for (String module : modules) store.put(module, "final", finalValue);
                store.append("chat", "last"); store.append("timeline", "only at close");
            });
            store.close();
            assertEquals("", store.error());
            Set<Path> expected = Set.of(journal(store, "chat"), journal(store, "early"), journal(store, "timeline"));
            assertEquals(expected, forces.keySet());
            for (Path file : expected) {
                assertEquals(1, forces.get(file).get());
                assertEquals(Files.readString(file, StandardCharsets.UTF_8), forcedContents.get(file));
            }
            assertEquals("\"first\"\n\"second\"\n\"last\"\n", forcedContents.get(journal(store, "chat")));
            for (String module : modules) {
                Path file = folder.resolve(module).resolve(SessionStore.checkpointName("final") + ".json");
                List<AtomicSave> synced = saves.stream().filter(save -> save.file().equals(file) && save.sync()).toList();
                assertEquals(module, 1, synced.size());
                byte[] expectedBytes = SessionStore.JSON.toJson(finalValue).getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(expectedBytes, synced.get(0).bytes());
                assertArrayEquals(expectedBytes, Files.readAllBytes(file));
            }
            AtomicSave finalMetadata = saves.get(saves.size() - 1);
            assertEquals(folder.resolve("session.json"), finalMetadata.file()); assertTrue(finalMetadata.sync());
            assertArrayEquals(finalMetadata.bytes(), Files.readAllBytes(finalMetadata.file()));
            SessionStore.Session session = SessionStore.JSON.fromJson(Files.readString(finalMetadata.file()), SessionStore.Session.class);
            assertEquals(store.currentId(), session.id); assertTrue(session.ended >= session.started);
            assertEquals("session.json", order.get(order.size() - 1));
            assertEquals("force", order.get(order.size() - 2));
            try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
                assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".tmp")));
            }
            store.close();
            for (AtomicInteger count : forces.values()) assertEquals(1, count.get());
        } finally { store.close(); }
    }
    @Test public void failedForceDoesNotThrowFromCloseAndOtherTouchedJournalsAreStillForced() throws Exception {
        Map<Path, AtomicInteger> forces = new ConcurrentHashMap<>();
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", file ->
            new FailingChannel(open(file), new AtomicBoolean(), new AtomicBoolean(), metadata -> {
                assertTrue(metadata);
                forces.computeIfAbsent(file, key -> new AtomicInteger()).incrementAndGet();
                if (file.getFileName().toString().equals("chat.jsonl")) throw new IOException("synthetic force failure");
            }));
        try {
            store.append("chat", "first"); store.flush();
            store.append("timeline", "second"); store.flush();
            store.close();
            assertTrue(store.error(), store.error().startsWith(SessionStore.UNSAVED_ON_CLOSE));
            assertEquals(Set.of(journal(store, "chat"), journal(store, "timeline")), forces.keySet());
            for (AtomicInteger count : forces.values()) assertEquals(1, count.get());
            Path metadata = store.directory().resolve(store.currentId()).resolve("session.json");
            assertTrue(Files.exists(metadata));
            SessionStore.Session session = SessionStore.JSON.fromJson(Files.readString(metadata), SessionStore.Session.class);
            assertTrue(session.ended > 0);
        } finally { store.close(); }
    }
    @Test public void failedSyncedCheckpointOrMetadataDoesNotThrowFromClose() throws Exception {
        for (boolean checkpoint : new boolean[]{true, false}) {
            SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", SessionWriteTest::open,
                (file, bytes, sync) -> {
                    if (sync && (checkpoint || file.getFileName().toString().equals("session.json")))
                        throw new IOException("synthetic synced write failure");
                    AtomicFiles.write(file, bytes, sync);
                });
            try {
                store.flush();
                if (checkpoint) store.collect("final", () -> { if (store.closing()) store.put("runs", "final", "saved"); });
                store.close();
                assertTrue(store.error(), store.error().startsWith(SessionStore.UNSAVED_ON_CLOSE));
            } finally { store.close(); }
        }
    }
    private record AtomicSave(Path file, byte[] bytes, boolean sync) { }
    @JsonAdapter(BadAdapter.class) private static final class Bad {
        final int kind;
        final AtomicBoolean retryReady = new AtomicBoolean();
        Bad(int kind) { this.kind = kind; }
    }
    public static final class BadAdapter extends TypeAdapter<Bad> {
        @Override public void write(JsonWriter out, Bad value) throws IOException {
            if (value.kind == 3) {
                if (!value.retryReady.get()) throw new IOException("temporary serialization IO failure");
                out.value("recovered"); return;
            }
            if (value.kind == 0) throw new JsonIOException("bad value");
            if (value.kind == 1) throw new IllegalStateException("bad value");
            throw new StackOverflowError("recursive value");
        }
        @Override public Bad read(JsonReader in) { throw new UnsupportedOperationException(); }
    }
    private static final class FailingChannel extends FileChannel {
        final FileChannel delegate;
        final AtomicBoolean failWrite, failRollback;
        final AtomicFiles.IOConsumer<Boolean> onForce;
        FailingChannel(FileChannel delegate, AtomicBoolean failWrite, AtomicBoolean failRollback) {
            this(delegate, failWrite, failRollback, metadata -> { });
        }
        FailingChannel(FileChannel delegate, AtomicBoolean failWrite, AtomicBoolean failRollback, AtomicFiles.IOConsumer<Boolean> onForce) {
            this.delegate=delegate; this.failWrite=failWrite; this.failRollback=failRollback;
            this.onForce=onForce;
        }
        @Override public int write(ByteBuffer src) throws IOException {
            if (!failWrite.get()) return delegate.write(src);
            ByteBuffer prefix=src.slice(); prefix.limit(Math.min(5,prefix.remaining())); delegate.write(prefix);
            throw new IOException("partial batch");
        }
        @Override public FileChannel truncate(long size) throws IOException {
            if (failRollback.compareAndSet(true,false)) throw new IOException("rollback failed once");
            delegate.truncate(size); return this;
        }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long value) throws IOException { delegate.position(value); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
        @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        @Override public long read(ByteBuffer[] dst,int offset,int length) throws IOException { return delegate.read(dst,offset,length); }
        @Override public int read(ByteBuffer dst,long position) throws IOException { return delegate.read(dst,position); }
        @Override public long write(ByteBuffer[] src,int offset,int length) throws IOException { return delegate.write(src,offset,length); }
        @Override public int write(ByteBuffer src,long position) throws IOException { return delegate.write(src,position); }
        @Override public void force(boolean metadata) throws IOException { onForce.accept(metadata); delegate.force(metadata); }
        @Override public long transferTo(long position,long count,WritableByteChannel target) throws IOException { return delegate.transferTo(position,count,target); }
        @Override public long transferFrom(ReadableByteChannel src,long position,long count) throws IOException { return delegate.transferFrom(src,position,count); }
        @Override public MappedByteBuffer map(MapMode mode,long position,long size) throws IOException { return delegate.map(mode,position,size); }
        @Override public FileLock lock(long position,long size,boolean shared) throws IOException { return delegate.lock(position,size,shared); }
        @Override public FileLock tryLock(long position,long size,boolean shared) throws IOException { return delegate.tryLock(position,size,shared); }
    }
}
